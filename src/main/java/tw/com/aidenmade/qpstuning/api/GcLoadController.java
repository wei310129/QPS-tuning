package tw.com.aidenmade.qpstuning.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/gc")
public class GcLoadController {

    /**
     * 測案 B：中等存活物件的暫存區（模擬快取/聚合資料）
     */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    /**
     * A. 小物件高分配（短命）：每次 request 製造大量小物件 + 少量回應
     *
     * query:
     * - mb: 目標分配量（MB），建議 5~50
     * - chunk: 每個小 byte[] 大小（bytes），建議 256~2048
     * - lists: 額外建立多少 List/Map/字串，增加物件數（非必需）
     */
    @GetMapping(value = "/alloc", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> alloc(
            @RequestParam(defaultValue = "20") int mb,
            @RequestParam(defaultValue = "1024") int chunk,
            @RequestParam(defaultValue = "2000") int lists
    ) {
        if (mb < 1) mb = 1;
        if (mb > 200) mb = 200; // 避免你一個請求把自己打爆
        if (chunk < 64) chunk = 64;
        if (chunk > 64 * 1024) chunk = 64 * 1024;

        long targetBytes = (long) mb * 1024 * 1024;
        long allocated = 0;

        // 用一個容器「短暫持有」，讓 allocation 真的發生；方法結束就釋放（短命）
        ArrayList<Object> holder = new ArrayList<>(Math.max(16, mb * 4));

        // 1) 大量小 byte[]（典型 request 組裝/序列化/DTO）
        while (allocated < targetBytes) {
            byte[] b = new byte[chunk];
            // 寫幾個 byte，避免過度被最佳化（雖然 JVM 不太會把 new 去掉，但保險）
            b[0] = 1;
            b[b.length - 1] = 2;
            holder.add(b);
            allocated += b.length;
        }

        // 2) 額外製造小物件：Map/List/String（更像真實業務）
        for (int i = 0; i < lists; i++) {
            Map<String, Object> m = new HashMap<>(4);
            m.put("id", i);
            m.put("ts", System.nanoTime());
            m.put("name", "user-" + i);
            m.put("flag", (i & 1) == 0);
            holder.add(m);

            List<String> l = new ArrayList<>(4);
            l.add("a");
            l.add("b");
            l.add("c" + i);
            holder.add(l);
        }

        // 回傳少量資料，避免網路 I/O 變瓶頸
        return Map.of(
                "case", "A-alloc",
                "targetMb", mb,
                "chunkBytes", chunk,
                "extraObjects", lists,
                "approxAllocatedBytes", allocated
        );
    }

    /**
     * B. 中等存活（模擬快取/聚合）
     *
     * 會把 payload 放進 cache，活到 ttlSeconds 才能被 cleanup 清掉
     * query:
     * - ttlSeconds: 存活秒數（建議 5~30）
     * - payloadKb: 每筆 payload 大小（KB），建議 64~512
     * - entries: 一次塞多少筆（建議 50~500）
     */
    @PostMapping(value = "/cache", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cachePut(
            @RequestParam(defaultValue = "15") int ttlSeconds,
            @RequestParam(defaultValue = "256") int payloadKb,
            @RequestParam(defaultValue = "200") int entries
    ) {
        if (ttlSeconds < 1) ttlSeconds = 1;
        if (ttlSeconds > 300) ttlSeconds = 300;
        if (payloadKb < 1) payloadKb = 1;
        if (payloadKb > 8192) payloadKb = 8192;
        if (entries < 1) entries = 1;
        if (entries > 5000) entries = 5000;

        long now = System.currentTimeMillis();
        long expireAt = now + Duration.ofSeconds(ttlSeconds).toMillis();

        int payloadBytes = payloadKb * 1024;
        long added = 0;

        for (int i = 0; i < entries; i++) {
            String key = "k-" + seq.incrementAndGet();
            // payload：byte[] + 字串（讓它更接近「快取一段資料」）
            byte[] payload = new byte[payloadBytes];
            payload[0] = 7;
            payload[payload.length - 1] = 9;

            String meta = ("meta-" + key + "-" + now).repeat(4);
            cache.put(key, new CacheEntry(expireAt, payload, meta));
            added++;
        }

        return Map.of(
                "case", "B-cache",
                "ttlSeconds", ttlSeconds,
                "payloadKb", payloadKb,
                "entriesAdded", added,
                "cacheSizeNow", cache.size()
        );
    }

    /**
     * B. Cleanup：把過期的 entry 清掉（你可以用排程做，但測案先手動）
     */
    @DeleteMapping(value = "/cache/cleanup", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cacheCleanup() {
        long now = System.currentTimeMillis();
        int before = cache.size();

        cache.entrySet().removeIf(e -> e.getValue().expireAtMillis <= now);

        int after = cache.size();
        return Map.of(
                "case", "B-cleanup",
                "before", before,
                "after", after,
                "removed", (before - after)
        );
    }

    /**
     * C. 大物件（humongous / large allocation）
     *
     * query:
     * - mb: 每次分配的大小（MB），建議 1~8
     * - repeat: 一次 request 做幾次（建議 1~10）
     *
     * 注意：我們用 holder 讓它在 request 期間存活，方法結束釋放。
     */
    @GetMapping(value = "/big", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> big(
            @RequestParam(defaultValue = "4") int mb,
            @RequestParam(defaultValue = "3") int repeat
    ) {
        if (mb < 1) mb = 1;
        if (mb > 64) mb = 64; // 先別太誇張
        if (repeat < 1) repeat = 1;
        if (repeat > 50) repeat = 50;

        int bytes = mb * 1024 * 1024;

        ArrayList<byte[]> holder = new ArrayList<>(repeat);
        long checksum = 0;

        for (int i = 0; i < repeat; i++) {
            byte[] b = new byte[bytes];
            b[0] = (byte) i;
            b[b.length - 1] = (byte) (i + 1);
            // 很小的 checksum，避免最佳化，並確保 JVM 真的碰到陣列
            checksum += (b[0] & 0xffL) + (b[b.length - 1] & 0xffL);
            holder.add(b);
        }

        return Map.of(
                "case", "C-big",
                "mbEach", mb,
                "repeat", repeat,
                "totalAllocatedMbThisReq", (long) mb * repeat,
                "checksum", checksum
        );
    }

    private record CacheEntry(long expireAtMillis, byte[] payload, String meta) {}
}