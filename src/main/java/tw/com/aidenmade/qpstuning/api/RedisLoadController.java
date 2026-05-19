package tw.com.aidenmade.qpstuning.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/redis")
public class RedisLoadController {

    private final RedissonClient redisson;
    private static final String KEY_PREFIX = "bench:";

    /**
     * A. 單筆 SET/GET RTT — 確認連線健康與基本延遲
     */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        String key = KEY_PREFIX + "ping";

        long t0 = System.nanoTime();
        redisson.<String>getBucket(key).set("pong");
        double writeMs = (System.nanoTime() - t0) / 1_000_000.0;

        long t1 = System.nanoTime();
        Object val = redisson.getBucket(key).get();
        double readMs = (System.nanoTime() - t1) / 1_000_000.0;

        redisson.getBucket(key).delete();

        return Map.of(
                "case", "A-ping",
                "writeLatencyMs", writeMs,
                "readLatencyMs", readMs,
                "value", String.valueOf(val)
        );
    }

    /**
     * B. 循序 SET 吞吐量
     *
     * query:
     * - keys: 寫入筆數 (1~10000)
     * - valueBytes: 每筆 value 大小 (1~65536 bytes)
     */
    @GetMapping("/write")
    public Map<String, Object> sequentialWrite(
            @RequestParam(defaultValue = "1000") int keys,
            @RequestParam(defaultValue = "256") int valueBytes
    ) {
        keys = clamp(keys, 1, 10_000);
        valueBytes = clamp(valueBytes, 1, 65_536);

        String value = "x".repeat(valueBytes);
        long errors = 0;

        long t0 = System.nanoTime();
        for (int i = 0; i < keys; i++) {
            try {
                redisson.<String>getBucket(KEY_PREFIX + "write:" + i).set(value);
            } catch (Exception e) {
                errors++;
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        cleanup("write:", keys);
        return buildResult("B-seq-write", keys, valueBytes, elapsedMs, errors);
    }

    /**
     * C. 循序 GET 吞吐量（先預填，再純讀）
     *
     * query:
     * - keys: 讀取筆數 (1~10000)
     * - valueBytes: 預填的 value 大小 (1~65536 bytes)
     */
    @GetMapping("/read")
    public Map<String, Object> sequentialRead(
            @RequestParam(defaultValue = "1000") int keys,
            @RequestParam(defaultValue = "256") int valueBytes
    ) {
        keys = clamp(keys, 1, 10_000);
        valueBytes = clamp(valueBytes, 1, 65_536);

        String value = "x".repeat(valueBytes);
        for (int i = 0; i < keys; i++) {
            redisson.<String>getBucket(KEY_PREFIX + "read:" + i).set(value);
        }

        long errors = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < keys; i++) {
            try {
                redisson.getBucket(KEY_PREFIX + "read:" + i).get();
            } catch (Exception e) {
                errors++;
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        cleanup("read:", keys);
        return buildResult("C-seq-read", keys, valueBytes, elapsedMs, errors);
    }

    /**
     * D. Pipeline Batch 寫入 — 一次 round-trip 送出多個命令，對比循序的效率
     *
     * query:
     * - keys: 寫入筆數 (1~10000)
     * - valueBytes: 每筆 value 大小 (1~65536 bytes)
     */
    @GetMapping("/batch")
    public Map<String, Object> batchWrite(
            @RequestParam(defaultValue = "1000") int keys,
            @RequestParam(defaultValue = "256") int valueBytes
    ) {
        keys = clamp(keys, 1, 10_000);
        valueBytes = clamp(valueBytes, 1, 65_536);

        String value = "x".repeat(valueBytes);
        long errors = 0;

        long t0 = System.nanoTime();
        try {
            RBatch batch = redisson.createBatch(BatchOptions.defaults());
            for (int i = 0; i < keys; i++) {
                batch.<String>getBucket(KEY_PREFIX + "batch:" + i).setAsync(value);
            }
            batch.execute();
        } catch (Exception e) {
            errors++;
            log.error("Batch write error", e);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        try {
            RBatch cleanupBatch = redisson.createBatch(BatchOptions.defaults());
            for (int i = 0; i < keys; i++) {
                cleanupBatch.getBucket(KEY_PREFIX + "batch:" + i).deleteAsync();
            }
            cleanupBatch.execute();
        } catch (Exception ignored) {}

        return buildResult("D-batch-write", keys, valueBytes, elapsedMs, errors);
    }

    /**
     * E. 並發混合讀寫 — 模擬真實服務壓力
     *
     * query:
     * - ops: 總操作數 (1~50000)
     * - readRatio: 讀的比例 0~100 (預設 70 = 七成讀三成寫)
     * - threads: 並發執行緒數 (1~100)
     */
    @GetMapping("/mixed")
    public Map<String, Object> mixed(
            @RequestParam(defaultValue = "2000") int ops,
            @RequestParam(defaultValue = "70") int readRatio,
            @RequestParam(defaultValue = "20") int threads
    ) throws InterruptedException {
        ops = clamp(ops, 1, 50_000);
        readRatio = clamp(readRatio, 0, 100);
        threads = clamp(threads, 1, 100);

        int poolSize = Math.min(ops, 500);
        String initValue = "v".repeat(256);
        for (int i = 0; i < poolSize; i++) {
            redisson.<String>getBucket(KEY_PREFIX + "mixed:" + i).set(initValue);
        }

        AtomicLong readOps = new AtomicLong();
        AtomicLong writeOps = new AtomicLong();
        AtomicLong errors = new AtomicLong();

        int finalReadRatio = readRatio;
        int finalPoolSize = poolSize;
        int finalOps = ops;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(ops);

        long t0 = System.nanoTime();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < ops; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    String key = KEY_PREFIX + "mixed:" + (idx % finalPoolSize);
                    if (ThreadLocalRandom.current().nextInt(100) < finalReadRatio) {
                        redisson.getBucket(key).get();
                        readOps.incrementAndGet();
                    } else {
                        redisson.<String>getBucket(key).set("w-" + idx);
                        writeOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();

        cleanup("mixed:", poolSize);

        long completed = readOps.get() + writeOps.get();
        double opsPerSec = elapsedMs > 0 ? (completed * 1000.0 / elapsedMs) : 0;
        return Map.of(
                "case", "E-mixed",
                "totalOps", finalOps,
                "completedOps", completed,
                "readOps", readOps.get(),
                "writeOps", writeOps.get(),
                "threads", threads,
                "readRatioPct", readRatio,
                "elapsedMs", elapsedMs,
                "opsPerSec", Math.round(opsPerSec),
                "errors", errors.get()
        );
    }

    private void cleanup(String suffix, int count) {
        try {
            RBatch batch = redisson.createBatch(BatchOptions.defaults());
            for (int i = 0; i < count; i++) {
                batch.getBucket(KEY_PREFIX + suffix + i).deleteAsync();
            }
            batch.execute();
        } catch (Exception e) {
            log.warn("Cleanup failed for prefix bench:{}{}", suffix, e.getMessage());
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static Map<String, Object> buildResult(String caseName, int ops, int valueBytes, long elapsedMs, long errors) {
        double opsPerSec = elapsedMs > 0 ? (ops * 1000.0 / elapsedMs) : 0;
        return Map.of(
                "case", caseName,
                "ops", ops,
                "valueBytesEach", valueBytes,
                "elapsedMs", elapsedMs,
                "opsPerSec", Math.round(opsPerSec),
                "errors", errors
        );
    }
}
