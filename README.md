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

## 工程重點與驗證入口

本專案用控制變因的負載觀察後端行為，不以單一 QPS 數字表示調校成功。Repository 提供實驗入口與監控設定，尚未附固定硬體、相同負載的前後效能報告。

| 重點 | 已實作與可觀察證據 | 判讀邊界 |
| --- | --- | --- |
| 跨實例流量控制 | GET /order 共用 Redis rate limiter（100 次／秒）與 semaphore（100 permits）；先限流，再限制並發 | 429 帶 Retry-After: 1，503 代表無法取得 permit；兩者不是同一種拒絕 |
| 請求延遲歸因 | /order 模擬約 500 ms 工作，Micrometer histogram 加上 order.processing 子 span，Prometheus／Grafana／Tempo 對照 | Trace 是定位工具；不能據此宣稱吞吐量或延遲已改善 |
| Redis 往返與批次取捨 | ping、循序 write/read、batch 與 mixed endpoints 回傳耗時和錯誤資訊 | 批次降低 RTT，但仍需控制總資料量、連線及並發，不能只比較不同規模的耗時 |
| 故障注入 | noeviction 寫入壓力與 Lua 忙等，再觀察 Sentinel 選舉和 Redisson 重連 | OOM 是寫入被拒絕，不等於 process 終止；忙等與失聯才是另一個故障因素 |
| JVM 行為 | GC endpoint 製造短命、快取與大物件配置 | 大物件是否屬 humongous 取決於 GC 與 region 設定；需和 heap／GC 指標一起判讀 |

來源：`src/main/java/tw/com/aidenmade/qpstuning/api/`、`filter/`、`config/RedissonConfig.java`、`src/main/resources/application.yaml`、`docker/grafana/provisioning/datasources/datasources.yaml`。

### 流量控制的實驗限制

- 100 次／秒、100 permits 是程式設定，不能當成量測得到的承載能力。/order 約 500 ms 的工作配合限流時，通常先看到 429；503 是否出現取決於實際在途請求及排程，不保證任何大量請求都會觸發。
- 每個應用實例首次使用 limiter 會呼叫 setRate；需評估新實例初始化對共用狀態的影響。
- Semaphore 在 finally 釋放 permit，未設計程序崩潰後的完整復原協議；初始化含 expiry 設定，應在多實例、閒置再流入及崩潰情境核對 permit 狀態，不能宣稱已驗證故障後的並發一致性。

## 技術棧

| 技術 | 說明 |
|------|------|
| Java 21 / Spring Boot 3.3.3 | Servlet 容器替換為 Undertow |
| Redisson 4.1.0 | RRateLimiter、RSemaphore、RBatch、Lua Script |
| Micrometer + Prometheus | HTTP latency histogram 及 JVM 指標，透過 `/actuator/prometheus` 暴露 |
| Grafana + Tempo | Metrics、OTLP trace 與 exemplar 導覽；`/order` 包含 `order.processing` 自訂 observation |
| Docker Compose | 本地一鍵啟動 Redis + Prometheus + Grafana + Tempo |
| Kubernetes | PoC 部署設定，Redis 使用 StatefulSet + Sentinel Sidecar（3 節點），監控堆疊部署於同一 namespace |

---

## 快速啟動（本地）

```bash
# 1. 啟動 Redis + Prometheus + Grafana + Tempo
# 所有指令都從 Repository 根目錄執行
docker compose -f docker/docker-compose.redis.yaml up -d

# 2. 啟動應用
./mvnw spring-boot:run
```

| 服務 | Container | Host Port | 說明 |
|------|-----------|-----------|------|
| 應用（Spring Boot） | — | `8080` | `./mvnw spring-boot:run` 啟動，不在 Compose 內 |
| Redis | `qps-redis` | `6379` | Redis 7 Alpine，AOF 持久化 |
| Prometheus | `qps-prometheus` | `9090` | 每 15s 抓取 `host.docker.internal:8080/actuator/prometheus` |
| Grafana | `qps-grafana` | `3000` | 帳密 admin / admin，Prometheus 與 Tempo 資料來源已由 provisioning 自動建立 |

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

## 可重現的觀察流程

1. 固定 JDK、GC、heap、CPU／記憶體限制、Redis mode、請求總量／並發、暖機時間與觀察區間。
2. 呼叫 GET /order，分別統計成功、429、503 與其他錯誤；讀取 /actuator/prometheus 的 HTTP histogram、JVM 和 GC 指標。
3. 在 Grafana 使用已 provision 的 Prometheus／Tempo，核對同一區間的 HTTP span 與 order.processing。預設 tracing sampling 為 1.0，量測開銷也需記入條件。
4. 以相同 keys／valueBytes 比較 Redis 循序和 batch 入口；先記錄基準，再一次只調整一個變因。
5. Sentinel 故障實驗只在可拋棄資料的獨立環境進行。/redis/oom-and-failover 會寫入 bench:oom:* 並執行阻塞 Lua；同時保留原 primary、Sentinel 日誌、ROLE／INFO replication、client 失敗與恢復時間。實驗後依 endpoint 說明執行 DELETE /redis/oom-cleanup。

本機 Compose 預設是單一 Redis，沒有 Sentinel；Failover 實驗需另外部署 Kubernetes 中的 Sentinel 拓樸並配置 REDIS_MODE=sentinel 等連線環境變數。Kubernetes image、storage 與網路需按實際環境準備，不代表直接具備正式環境可用性。

| 報告欄位 | 應保存的內容 |
| --- | --- |
| 環境與輸入 | commit、JDK／GC／heap、部署拓樸、請求分布、資料量、暖機及時間區間 |
| 成功與失敗 | 成功 QPS、429／503／例外數；不能把拒絕請求全算為成功吞吐量 |
| 延遲與資源 | 同一區間 P50／P95／P99、CPU、heap、GC pause、Redis latency／errors |
| 故障 | 注入方式、原／新 primary、應用失敗區間、重連後讀寫與資料狀態 |

## 自動化測試範圍

Windows：`.\mvnw.cmd test`；Linux／macOS：`./mvnw test`。目前只有 Spring Boot context 測試，不能代替跨實例限流、Redis 負載、Sentinel Failover 或壓力測試；以上流程是驗證方法，沒有宣稱本次文件更新已重新執行環境實驗。
