package tw.com.aidenmade.qpstuning.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
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

    @GetMapping("/batch-max")
    public Map<String, Object> batchMaxWrite(
            @RequestParam(defaultValue = "1000") int keys,
            @RequestParam(defaultValue = "256") int valueBytes
    ) {
        keys = clamp(keys, 1, 10_000);
        valueBytes = clamp(valueBytes, 1, 65_536);

        String requestId = UUID.randomUUID().toString();
        String value = "x".repeat(valueBytes);
        long errors = 0;

        long t0 = System.nanoTime();

        try {
            RBatch batch = redisson.createBatch(BatchOptions.defaults());

            for (int i = 0; i < keys; i++) {
                String key = KEY_PREFIX + "batch:" + requestId + ":" + i;
                batch.<String>getBucket(key).setAsync(value);
            }

            batch.execute();
        } catch (Exception e) {
            errors++;
            log.error("Batch write error", e);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        return buildResult("D-batch-write", keys, valueBytes, elapsedMs, errors);
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

    /**
     * F. OOM + Sentinel Failover 複合壓測
     *
     * 設計目標（三者同時達成）：
     *   1. Redis OOM   — 批次寫入資料直到 maxmemory=64MB（noeviction），後續寫入收到 OOM 錯誤
     *   2. Sentinel failover — Lua 忙等迴圈長時間佔用 event loop，Sentinel 偵測 master 無有效回應 → failover
     *   3. 不被 k8s OOM Kill — Redis 實際記憶體維持 ~64MB，遠低於 k8s limit 256MB
     *
     * Sentinel 觸發原理：
     *   - Redis Lua 執行期間，event loop 被佔用，Sentinel PING 無法得到有效回應（+PONG）
     *   - 前 lua-time-limit(5s)：PING 進 OS 緩衝佇列、無任何回應
     *   - 超過 lua-time-limit   ：Redis 回傳 BUSY 錯誤（不算有效 PONG，last_pong_time 不更新）
     *   - 超過 down-after-milliseconds(5s) 後：Sentinel 標記 S_DOWN → 達法定人數 → O_DOWN → failover
     *
     * 呼叫端注意：
     *   - Redisson 預設 timeout(3s) 到期後會拋出例外，屬於預期行為
     *   - Failover 完成後需呼叫 DELETE /redis/oom-cleanup 清除測試資料
     *
     * query:
     *   fillMb       — 預填資料量(MB)，建議 55~63，不超過 Redis maxmemory 64MB，預設 60
     *   blockSeconds — Lua 阻塞秒數，必須 > sentinel down-after-ms(5s)，建議 7~15，預設 7
     */
    @GetMapping("/oom-and-failover")
    public Map<String, Object> oomAndFailover(
            @RequestParam(defaultValue = "60") int fillMb,
            @RequestParam(defaultValue = "7") int blockSeconds
    ) {
        fillMb       = clamp(fillMb, 10, 63);
        blockSeconds = clamp(blockSeconds, 6, 30);

        final int VALUE_SIZE = 65_536;                             // 64KB per key
        final int targetKeys = (fillMb * 1024 * 1024) / VALUE_SIZE;
        final String value   = "x".repeat(VALUE_SIZE);
        // 每批 10 keys × 64KB = 640KB，遠低於 Netty direct buffer 上限
        // 原本 200 × 64KB = 12.8MB 會超過預設 10MB MaxDirectMemorySize
        final int BATCH_SIZE = 10;

        long fillWritten = 0;
        long fillErrors  = 0;
        long t0          = System.nanoTime();

        // ── Step 1: 批次寫入直到 Redis 記憶體達到 maxmemory ───────────────────
        log.info("[oom-and-failover] Step1 start: fill {} MB ({} keys × 64KB)", fillMb, targetKeys);
        for (int start = 0; start < targetKeys; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, targetKeys);
            RBatch batch = redisson.createBatch(BatchOptions.defaults());
            for (int i = start; i < end; i++) {
                batch.<String>getBucket(KEY_PREFIX + "oom:" + i).setAsync(value);
            }
            try {
                batch.execute();
                fillWritten += (end - start);
            } catch (Exception e) {
                fillErrors++;
                log.warn("[oom-and-failover] Step1 batch {}-{} error (Redis OOM?): {}",
                        start, end, e.getMessage());
                break;   // Redis OOM，不再繼續寫入
            }
        }

        long fillElapsedMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("[oom-and-failover] Step1 done: written={} keys, errors={}, elapsed={}ms",
                fillWritten, fillErrors, fillElapsedMs);

        // ── Step 2: Lua 忙等迴圈 — 持續佔用 Redis event loop ───────────────────
        // 每次 redis.call('TIME') 都必須通過 Redis 命令分派器，
        // 確保 event loop 真的被佔用，不被 JIT 或 CPU 最佳化跳過
        final String luaScript =
                "local s    = redis.call('TIME')\n" +
                "local s_us = tonumber(s[1]) * 1000000 + tonumber(s[2])\n" +
                "local dur  = tonumber(ARGV[1]) * 1000000\n" +
                "local i    = 0\n" +
                "repeat\n" +
                "  i = i + 1\n" +
                "  local n    = redis.call('TIME')\n" +
                "  local n_us = tonumber(n[1]) * 1000000 + tonumber(n[2])\n" +
                "until (n_us - s_us) >= dur\n" +
                "return i";

        log.info("[oom-and-failover] Step2 start: Lua busy-loop {} seconds", blockSeconds);
        String luaStatus = "completed";
        String luaError  = null;
        try {
            // Redisson 預設 timeout(3s) 會先於 Lua 完成而到期並拋出例外，屬預期行為
            // Redis 端 Lua 仍繼續執行直到計時結束，確保 Sentinel 能偵測到 master 無回應
            redisson.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    luaScript,
                    RScript.ReturnType.LONG,
                    Collections.emptyList(),
                    String.valueOf(blockSeconds)
            );
        } catch (Exception e) {
            luaStatus = "client_timeout_or_failover";
            luaError  = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("[oom-and-failover] Step2 exception (expected — Redisson timeout while Redis blocks): {}",
                    e.getMessage());
        }

        long totalElapsedMs = (System.nanoTime() - t0) / 1_000_000;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case",                  "F-oom-and-failover");
        result.put("step1_fillMb",          fillMb);
        result.put("step1_targetKeys",      targetKeys);
        result.put("step1_keysWritten",     fillWritten);
        result.put("step1_batchErrors",     fillErrors);
        result.put("step1_fillElapsedMs",   fillElapsedMs);
        result.put("step2_blockSeconds",    blockSeconds);
        result.put("step2_luaStatus",       luaStatus);
        result.put("step2_note",
                "Sentinel down-after-ms=5000; failover expected ~5s after Lua starts. " +
                "Run DELETE /redis/oom-cleanup after new master is elected.");
        result.put("totalElapsedMs",        totalElapsedMs);
        if (luaError != null) result.put("step2_error", luaError);
        return result;
    }

    /**
     * G. 清理 OOM 測試資料
     *
     * 在 Sentinel failover 完成、新 master 上線後呼叫，
     * 刪除 /redis/oom-and-failover 寫入的所有 bench:oom:* keys。
     *
     * query:
     *   maxKeys — 清除上限筆數（需 >= oom-and-failover 實際寫入量），預設 1000
     */
    @DeleteMapping("/oom-cleanup")
    public Map<String, Object> oomCleanup(
            @RequestParam(defaultValue = "1000") int maxKeys
    ) {
        maxKeys = clamp(maxKeys, 1, 5_000);
        long t0 = System.nanoTime();
        long deleted = 0;
        long errors  = 0;

        try {
            RBatch batch = redisson.createBatch(BatchOptions.defaults());
            for (int i = 0; i < maxKeys; i++) {
                batch.getBucket(KEY_PREFIX + "oom:" + i).deleteAsync();
            }
            batch.execute();
            deleted = maxKeys;
        } catch (Exception e) {
            errors++;
            log.warn("[oom-cleanup] Batch delete error: {}", e.getMessage());
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return Map.of(
                "case",       "G-oom-cleanup",
                "maxKeys",    deleted,
                "errors",     errors,
                "elapsedMs",  elapsedMs
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
