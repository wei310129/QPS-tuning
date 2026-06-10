# QPS-tuning

一個用於觀察與調校後端效能行為的實驗平台。

| 實作目標 | 說明 |
|----------|------|
| 分散式限流與並發控制 | 以 Redis 為後端，驗證多實例部署下 Rate Limit 與 Semaphore 的跨實例一致性 |
| JVM GC 壓力模擬 | 分別製造短命小物件、中等存活物件（快取場景）、大物件（humongous allocation），觀察不同 GC 策略的表現 |
| Redis 效能基準 | 涵蓋單筆 RTT、循序讀寫吞吐量、Pipeline Batch 效率、並發混合讀寫，提供可重複執行的量測資料 |
| Redis OOM + Sentinel Failover | 在 `noeviction` 策略下觸發 OOM，並以 Lua 忙等佔用 event loop，驗證 Sentinel 的 failover 偵測與切換流程 |
| 即時效能監控 | 透過 Micrometer + Prometheus + Grafana，觀察 HTTP 延遲分佈、錯誤率與 JVM 指標 |

---

## 技術棧

| 技術 | 說明 |
|------|------|
| Java 21 / Spring Boot 3.3.3 | Servlet 容器替換為 Undertow |
| Redisson 4.1.0 | RRateLimiter、RSemaphore、RBatch、Lua Script |
| Micrometer + Prometheus | HTTP latency histogram 及 JVM 指標，透過 `/actuator/prometheus` 暴露 |
| Grafana | 連接 Prometheus 進行視覺化 |
| Docker Compose | 本地一鍵啟動 Redis + Prometheus + Grafana |
| Kubernetes | 正式部署環境，Redis 使用 StatefulSet + Sentinel Sidecar（3 節點），監控堆疊部署於同一 namespace |

---

## 快速啟動（本地）

```bash
# 1. 啟動 Redis + Prometheus + Grafana
cd docker
docker compose -f docker-compose.redis.yaml up -d

# 2. 啟動應用
./mvnw spring-boot:run
```

| 服務 | Container | Host Port | 說明 |
|------|-----------|-----------|------|
| 應用（Spring Boot） | — | `8080` | `./mvnw spring-boot:run` 啟動，不在 Compose 內 |
| Redis | `qps-redis` | `6379` | Redis 7 Alpine，AOF 持久化 |
| Prometheus | `qps-prometheus` | `9090` | 每 15s 抓取 `host.docker.internal:8080/actuator/prometheus` |
| Grafana | `qps-grafana` | `3000` | 帳密 admin / admin，資料來源需手動指向 `http://prometheus:9090` |

---

## Kubernetes 部署

```bash
kubectl apply -k kubernetes/
```

部署內容（namespace：`front-mpos`）：

| 元件 | Service 名稱 | Type | Port | 說明 |
|------|-------------|------|------|------|
| 應用 | `qps-tuning-service` | ClusterIP | `8080` | 應用主體，ConfigMap 注入 Redis Sentinel 位址與 JVM 參數 |
| Redis（資料） | `mps-redis-ha-headless` | Headless | `6379` | StatefulSet 3 Pod 各自的穩定 DNS，節點間互通用 |
| Redis Sentinel | `mps-redis-ha-headless` | Headless | `26379` | Sentinel sidecar，與 Redis 同 Pod 共用 headless DNS |
| Sentinel（App 連線） | `mps-redis-ha-sentinel-service` | ClusterIP | `26379` | Redisson 連線入口，VIP 分流到 3 個 Sentinel sidecar |
| Prometheus | `prometheus-service` | ClusterIP | `9090` | 抓取 `qps-tuning-service:8080/actuator/prometheus` |
| Grafana | `grafana-service` | ClusterIP | `3000` | 帳密 admin / admin |

**存取監控介面**（ClusterIP，需 port-forward）：

```bash
kubectl port-forward -n front-mpos svc/prometheus-service 9090:9090
kubectl port-forward -n front-mpos svc/grafana-service 3000:3000
```

---

## Heap Dump + Eclipse MAT 分析

`/heap/*` 端點用於在 JVM heap 製造特定記憶體模式，搭配 [Eclipse Memory Analyzer (MAT)](https://eclipse.dev/mat/) 進行離線分析，適合學習以下概念：

| 概念 | 說明 |
|------|------|
| **Shallow Heap** | 物件本身佔用的記憶體（不含其引用的子物件） |
| **Retained Heap** | 若此物件被 GC 回收，可一併釋放的記憶體總量 |
| **Dominator Tree** | MAT 計算出的支配關係樹，可快速定位「誰獨佔最多記憶體」 |
| **Duplicate Strings** | heap 中值相同但各自獨立的 String 物件，可透過 `intern()` 消除浪費 |

### 標準工作流程

```
1. 呼叫 case 端點（H / I / J / K）→ 在 heap 建立特定模式
2. POST /heap/dump                 → 觸發 .hprof 輸出
3. MAT 開啟 hprof                  → 依各 case 說明操作
4. DELETE /heap/reset              → 清除 static 狀態，讓 GC 回收
```

> **替代 dump 方式（不需呼叫 API）**
> ```bash
> # 本地
> jcmd <pid> GC.heap_dump /tmp/heap.hprof
>
> # Kubernetes
> kubectl exec <pod> -- jcmd 1 GC.heap_dump /tmp/heap.hprof
> kubectl cp <pod>:/tmp/heap.hprof ./heap.hprof
> ```

---

### Case H — Duplicate Strings（無 intern）

**目的**：在 heap 製造大量值相同、但各自獨立的 String 物件，觀察記憶體浪費。

```bash
# 建立 20000 個長度 128 的重複 String（預設）
curl "http://localhost:8080/heap/string-dup"

# 自訂數量與長度
curl "http://localhost:8080/heap/string-dup?count=50000&length=256"
```

| 參數 | 預設 | 說明 |
|------|------|------|
| `count` | 20000 | 建立的 String 物件數量（1 ～ 500000） |
| `length` | 128 | 每個 String 的字元長度（8 ～ 4096） |

**MAT 操作**：
- `File → Run Expert System Test → "Duplicate Strings"` → 查看 **Waste** 欄位（浪費的 bytes）
- OQL 查詢：`SELECT toString(s) FROM java.lang.String s WHERE toString(s).startsWith("DDDD")`

---

### Case I — String.intern()（String Pool 共享）

**目的**：對比 Case H，透過 `intern()` 讓所有 reference 指向 String pool 中的同一個實例，heap 中只有 1 個 String 物件。

```bash
# 建立 20000 個 reference，但只有 1 個 String 物件
curl "http://localhost:8080/heap/string-intern"

curl "http://localhost:8080/heap/string-intern?count=50000&length=256"
```

| 參數 | 預設 | 說明 |
|------|------|------|
| `count` | 20000 | 放入 list 的 reference 數量（建議與 Case H 一致以利對比） |
| `length` | 128 | String 的字元長度 |

**MAT 操作**：
- `Histogram → java.lang.String` → 比較 **Objects** 欄位
  - Case H：`count` 個物件
  - Case I：僅 **1** 個物件（+ ArrayList 的 reference slot）

---

### Case J — Dominator Tree（支配樹）

**目的**：建立多層樹結構，展示「shallow size 很小、retained size 卻極大」的支配關係。Root 節點 shallow ≈ 32 bytes，但支配所有後代 → retained = 所有葉節點 payload 總和。

```bash
# 50 條分支 × 深度 5 × 64KB payload/葉（預設）
curl "http://localhost:8080/heap/dominator"

# 100 條分支 × 深度 4 × 128KB payload
curl "http://localhost:8080/heap/dominator?branches=100&depth=4&payloadKb=128"
```

| 參數 | 預設 | 說明 |
|------|------|------|
| `branches` | 50 | Root 長出幾條分支（1 ～ 500） |
| `depth` | 5 | 每條分支的深度（1 ～ 20） |
| `payloadKb` | 64 | 每個葉節點的 byte[] 大小（1 ～ 512 KB） |

**MAT 操作**：
- `Window → Heap Dump Details → Dominator Tree` → 找 `DominatorNode root-N`，觀察 **Retained Heap %** 極高
- 右鍵 → `Show Retained Set`：查看所有由 Root 獨佔支配的物件
- 右鍵 → `Show objects by class`：分析後代物件分布

---

### Case K — Shallow vs Retained（獨佔 vs 共享 payload）

**目的**：對比兩種 wrapper 的 retained size 差異：
- **Exclusive Wrapper**：各自持有獨立 byte[]，retained ≈ shallow + payloadBytes
- **Shared Wrapper**：所有 wrapper 共用同一 byte[]，retained ≈ shallow（~32 bytes）

```bash
# 30 個 exclusive + 30 個 shared，payload 各 128KB（預設）
curl "http://localhost:8080/heap/shallow-retained"

curl "http://localhost:8080/heap/shallow-retained?exclusiveCount=50&sharedCount=50&payloadKb=256"
```

| 參數 | 預設 | 說明 |
|------|------|------|
| `exclusiveCount` | 30 | 建立幾個獨佔 wrapper（1 ～ 200） |
| `sharedCount` | 30 | 建立幾個共享 wrapper（1 ～ 200） |
| `payloadKb` | 128 | 每個 payload 大小（1 ～ 256 KB） |

**MAT 操作**：
- `Histogram → HeapDumpController$SizeWrapper` → 開啟 **Retained Heap** 欄位
  - `excl-*`：retained 遠大於 `shar-*`
- 右鍵 → `List Objects → with outgoing references` → 觀察 exclusive 持有私有 byte[]，shared 指向同一個 byte[] 物件
- OQL：`SELECT s FROM tw.com.aidenmade.qpstuning.api.HeapDumpController$SizeWrapper s`

---

### 觸發 Heap Dump

```bash
# 輸出到 /tmp/heap-dump.hprof（liveOnly=true，只含存活物件）
curl -X POST "http://localhost:8080/heap/dump"

# 自訂路徑（Windows）
curl -X POST "http://localhost:8080/heap/dump?path=C:/Temp/heap.hprof"

# 含已死物件的完整快照（檔案較大）
curl -X POST "http://localhost:8080/heap/dump?liveOnly=false"
```

| 參數 | 預設 | 說明 |
|------|------|------|
| `path` | `/tmp/heap-dump.hprof` | hprof 輸出路徑 |
| `liveOnly` | `true` | `true` = 只含存活物件（較小）；`false` = 完整快照 |

---

### 清除狀態

```bash
# 清除所有 case 持有的 static 物件，讓 GC 可以回收
curl -X DELETE "http://localhost:8080/heap/reset"
```

---

### 端點總覽

| Case | 端點 | 方法 | MAT 分析功能 |
|------|------|------|-------------|
| H | `/heap/string-dup` | GET | Duplicate Strings（Expert System Test） |
| I | `/heap/string-intern` | GET | Histogram — Objects 數量對比 |
| J | `/heap/dominator` | GET | Dominator Tree — Retained Heap % |
| K | `/heap/shallow-retained` | GET | Histogram — Retained Heap 欄位 |
| — | `/heap/dump` | POST | 觸發 HotSpot heap dump → .hprof |
| — | `/heap/reset` | DELETE | 清除 static 狀態，釋放記憶體 |

