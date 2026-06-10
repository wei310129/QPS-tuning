package tw.com.aidenmade.qpstuning.api;

import com.sun.management.HotSpotDiagnosticMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heap Dump 測試場景 — 搭配 Eclipse MAT (Memory Analyzer Tool) 分析
 *
 * 工作流程：
 *   1. 呼叫目標 case 端點（H / I / J / K）在 heap 製造特定模式
 *   2. 呼叫 POST /heap/dump 觸發 hprof 輸出（或用 jcmd <pid> GC.heap_dump <path>）
 *   3. 用 MAT 開啟 hprof，依各 case 的 matHint 操作
 *   4. 呼叫 DELETE /heap/reset 清除 static 狀態，讓 GC 回收
 */
@Slf4j
@RestController
@RequestMapping("/heap")
public class HeapDumpController {

    // ── GC roots — static 欄位讓物件存活到 /heap/reset 被呼叫 ─────────────
    private static final List<String>      DUP_STRINGS    = Collections.synchronizedList(new ArrayList<>());
    private static final List<String>      INTERN_REFS    = Collections.synchronizedList(new ArrayList<>());
    private static volatile DominatorNode  DOMINATOR_ROOT = null;
    private static final List<SizeWrapper> SIZE_WRAPPERS  = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger     SEQ            = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────────────
    // Case H: Duplicate Strings（無 intern）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 在 heap 製造大量內容相同、但各自獨立的 String 物件。
     *
     * MAT 操作：
     *   File → Run Expert System Test → "Duplicate Strings"
     *   可看到相同值佔用多少額外記憶體（Waste 欄位）
     *
     * OQL 查詢範例：
     *   SELECT toString(s) FROM java.lang.String s WHERE toString(s).startsWith("DDDD")
     *
     * query:
     * - count:  要建立的 String 物件數量（建議 5000～100000）
     * - length: 每個 String 的字元長度（建議 32～512）
     */
    @GetMapping("/string-dup")
    public Map<String, Object> stringDup(
            @RequestParam(defaultValue = "20000") int count,
            @RequestParam(defaultValue = "128")   int length
    ) {
        count  = clamp(count,  1, 500_000);
        length = clamp(length, 8, 4_096);

        // 固定內容：MAT 的 Duplicate Strings 以「值」為 key 做分組
        char[] chars = new char[length];
        Arrays.fill(chars, 'D');

        for (int i = 0; i < count; i++) {
            // new String(char[]) 在 heap 建立獨立物件，不走 String pool
            DUP_STRINGS.add(new String(chars));
        }

        // Java 11+ Compact Strings：Latin-1 用 1 byte/char；加上 String header（~32 bytes）與 byte[] header（~16 bytes）
        long approxWastedBytes = (long) count * (length + 48L);

        return buildResult("H-string-dup", Map.of(
                "addedStrings",      count,
                "totalHeld",         DUP_STRINGS.size(),
                "stringLength",      length,
                "approxWastedBytes", approxWastedBytes,
                "matHint",          "MAT → File → Run Expert System Test → Duplicate Strings"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case I: String.intern() — String Pool 共享對比
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 將相同值 intern 後，所有 reference 指向 String pool 中的單一實例。
     * 對比 Case H，MAT Histogram 中 java.lang.String 的物件數遠低於 H。
     *
     * MAT 操作：
     *   Histogram → 找 java.lang.String → 比較 Objects 欄位
     *   H 有 count 個物件；I 只有 1 個物件（+ INTERN_REFS ArrayList 的 reference slots）
     *
     * query:
     * - count:  放入 list 的 reference 數（建議與 Case H 一致，方便對比）
     * - length: String 的字元長度
     */
    @GetMapping("/string-intern")
    public Map<String, Object> stringIntern(
            @RequestParam(defaultValue = "20000") int count,
            @RequestParam(defaultValue = "128")   int length
    ) {
        count  = clamp(count,  1, 500_000);
        length = clamp(length, 8, 4_096);

        char[] chars = new char[length];
        Arrays.fill(chars, 'I');
        String interned = new String(chars).intern(); // 放入 String pool，後續 intern 同值會回傳同一實例

        for (int i = 0; i < count; i++) {
            INTERN_REFS.add(interned); // 只新增 reference slot，不建立新 String 物件
        }

        return buildResult("I-string-intern", Map.of(
                "addedRefs",    count,
                "totalHeld",    INTERN_REFS.size(),
                "stringLength", length,
                "note",         "All " + count + " refs → 1 String instance in pool",
                "matHint",     "MAT Histogram: java.lang.String Objects << H-string-dup 的數量"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case J: Dominator Tree — 支配樹場景
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 建立以 DOMINATOR_ROOT 為根的多層樹結構，展示支配關係：
     *   Root 支配所有後代 → Root retained size = 所有後代記憶體的總和
     *   Root shallow size ≈ 32 bytes（只有 label + children List 指標）
     *
     * 結構：Root → branches 條分支 → (depth-1 層中間節點) → Leaf（持有 byte[] payload）
     *
     * MAT 操作：
     *   Window → Heap Dump Details → Dominator Tree
     *     → DOMINATOR_ROOT 出現在頂端，Retained Heap % 很高
     *   右鍵 DOMINATOR_ROOT → "Show Retained Set" 查看所有由它獨佔支配的物件
     *   右鍵 DOMINATOR_ROOT → "Show objects by class" → 分析後代分布
     *
     * query:
     * - branches:  從 Root 長出幾條分支（建議 10～200）
     * - depth:     每條分支的深度（建議 3～10）
     * - payloadKb: 每個葉節點的 byte[] 大小（建議 32～256 KB）
     */
    @GetMapping("/dominator")
    public Map<String, Object> dominator(
            @RequestParam(defaultValue = "50")  int branches,
            @RequestParam(defaultValue = "5")   int depth,
            @RequestParam(defaultValue = "64")  int payloadKb
    ) {
        branches  = clamp(branches,  1, 500);
        depth     = clamp(depth,     1, 20);
        payloadKb = clamp(payloadKb, 1, 512);

        int payloadBytes = payloadKb * 1024;
        DominatorNode root = new DominatorNode("root-" + SEQ.incrementAndGet(), null);
        long leafCount = 0;

        for (int b = 0; b < branches; b++) {
            DominatorNode current = root;
            for (int d = 0; d < depth; d++) {
                boolean isLeaf = (d == depth - 1);
                DominatorNode child = new DominatorNode(
                        "b" + b + "-d" + d,
                        isLeaf ? new byte[payloadBytes] : null  // 只有葉節點持有 payload
                );
                current.children.add(child);
                current = child;
                if (isLeaf) leafCount++;
            }
        }

        DOMINATOR_ROOT = root;

        long approxRetainedKb = leafCount * payloadKb;
        return buildResult("J-dominator", Map.of(
                "branches",         branches,
                "depth",            depth,
                "payloadKbEach",    payloadKb,
                "leafNodes",        leafCount,
                "approxRetainedKb", approxRetainedKb,
                "matHint",         "MAT → Window → Heap Dump Details → Dominator Tree → 找 DominatorNode root-N"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case K: Shallow Heap vs Retained Size 對比
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 建立兩種 wrapper：
     *
     * Exclusive Wrapper（獨佔 payload）：
     *   shallow ≈ 32 bytes  retained ≈ 32 + payloadBytes（payload 只被它自己持有）
     *
     * Shared Wrapper（共享同一 byte[]）：
     *   shallow ≈ 32 bytes  retained ≈ 32 bytes
     *   （shared byte[] 同時被多個 wrapper 引用，不屬於任一 wrapper 的 retained set）
     *
     * MAT 操作：
     *   Histogram → 找 HeapDumpController$SizeWrapper
     *     → 開啟 "Retained Heap" 欄位
     *     → 可看到 exclusive 的 retained >> shared 的 retained
     *   右鍵 → List Objects → with outgoing references
     *     → 觀察 exclusive 有私有 byte[]，shared 指向同一個 byte[] 物件
     *
     * OQL：
     *   SELECT s FROM tw.com.aidenmade.qpstuning.api.HeapDumpController$SizeWrapper s
     *
     * query:
     * - exclusiveCount: 建立幾個 exclusive wrapper
     * - sharedCount:    建立幾個 shared wrapper
     * - payloadKb:      每個 payload 大小（建議 64～256 KB）
     */
    @GetMapping("/shallow-retained")
    public Map<String, Object> shallowRetained(
            @RequestParam(defaultValue = "30")  int exclusiveCount,
            @RequestParam(defaultValue = "30")  int sharedCount,
            @RequestParam(defaultValue = "128") int payloadKb
    ) {
        exclusiveCount = clamp(exclusiveCount, 1, 200);
        sharedCount    = clamp(sharedCount,    1, 200);
        payloadKb      = clamp(payloadKb,      1, 256);

        int payloadBytes = payloadKb * 1024;

        // A: 每個 wrapper 各自 new byte[]，自己獨佔 → retained 包含 payload
        for (int i = 0; i < exclusiveCount; i++) {
            SIZE_WRAPPERS.add(new SizeWrapper("excl-" + i, new byte[payloadBytes], "exclusive"));
        }

        // B: 所有 wrapper 共用同一個 byte[] → 任一 wrapper 都不能獨佔它 → retained 不含 payload
        byte[] sharedPayload = new byte[payloadBytes];
        for (int i = 0; i < sharedCount; i++) {
            SIZE_WRAPPERS.add(new SizeWrapper("shar-" + i, sharedPayload, "shared"));
        }

        long exclusiveTotalKb = (long) exclusiveCount * payloadKb;
        return buildResult("K-shallow-retained", Map.of(
                "exclusiveWrappers",         exclusiveCount,
                "sharedWrappers",            sharedCount,
                "payloadKbEach",             payloadKb,
                "totalWrappers",             SIZE_WRAPPERS.size(),
                "exclusiveTotalRetainedKb",  exclusiveTotalKb,
                "sharedRetainedKbEach",      "~0 (shared payload 不被任何單一 wrapper 獨佔)",
                "matHint",                  "MAT Histogram → SizeWrapper → 開 Retained Heap 欄位比較"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trigger: 觸發 JVM heap dump
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 觸發 JVM heap dump，輸出 .hprof 檔案供 MAT 分析。
     *
     * 建議流程：先呼叫 case 端點製造 heap 模式 → 呼叫此端點 → 用 MAT 開啟 hprof
     *
     * 替代方式（不需呼叫此端點）：
     *   jcmd <pid> GC.heap_dump /tmp/heap.hprof
     *   kubectl exec <pod> -- jcmd 1 GC.heap_dump /tmp/heap.hprof
     *   kubectl cp <pod>:/tmp/heap.hprof ./heap.hprof
     *
     * query:
     * - path:     hprof 輸出路徑（K8s 預設 /tmp/heap-dump.hprof；Windows 用 C:/Temp/heap-dump.hprof）
     * - liveOnly: true = 只包含存活物件（較小，MAT 速度快）；false = 完整快照含 GC 可回收物件
     */
    @PostMapping("/dump")
    public Map<String, Object> dump(
            @RequestParam(defaultValue = "/tmp/heap-dump.hprof") String path,
            @RequestParam(defaultValue = "true") boolean liveOnly
    ) {
        try {
            File f = new File(path);
            if (f.exists() && !f.delete()) {
                return Map.of("error", "Cannot delete existing file: " + path);
            }

            HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
                    ManagementFactory.getPlatformMBeanServer(),
                    "com.sun.management:type=HotSpotDiagnostic",
                    HotSpotDiagnosticMXBean.class
            );
            mxBean.dumpHeap(path, liveOnly);

            long sizeBytes = f.exists() ? f.length() : -1L;
            log.info("[heap-dump] written {} bytes ({} MB) to {}", sizeBytes, sizeBytes / (1024 * 1024), path);

            return Map.of(
                    "case",      "heap-dump",
                    "path",      path,
                    "liveOnly",  liveOnly,
                    "sizeBytes", sizeBytes,
                    "sizeMb",    sizeBytes / (1024 * 1024),
                    "k8sHint",  "kubectl cp <pod>:" + path + " ./heap-dump.hprof"
            );
        } catch (Exception e) {
            log.error("[heap-dump] failed", e);
            return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reset: 清除所有 static 狀態
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 清除所有 case 的 static 持有狀態，讓 GC 可以回收製造的物件。
     * 建議：hprof 分析完後呼叫，避免 heap 膨脹。
     */
    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        int dup     = DUP_STRINGS.size();
        int intern  = INTERN_REFS.size();
        boolean dom = DOMINATOR_ROOT != null;
        int wrap    = SIZE_WRAPPERS.size();

        DUP_STRINGS.clear();
        INTERN_REFS.clear();
        DOMINATOR_ROOT = null;
        SIZE_WRAPPERS.clear();

        return Map.of(
                "case",               "reset",
                "clearedDupStrings",  dup,
                "clearedInternRefs",  intern,
                "clearedDominator",   dom,
                "clearedSizeWrappers", wrap
        );
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    static class DominatorNode {
        final String label;
        final byte[] payload;
        final List<DominatorNode> children = new ArrayList<>();

        DominatorNode(String label, byte[] payload) {
            this.label   = label;
            this.payload = payload;
        }
    }

    record SizeWrapper(String label, byte[] payload, String type) {}

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static Map<String, Object> buildResult(String caseName, Map<String, Object> extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", caseName);
        result.putAll(extra);
        return result;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
