# QPS-tuning

一個用於觀察與調校後端效能行為的實驗平台，實作目標涵蓋：

- **分散式限流與並發控制**：以 Redis 為後端，驗證多實例部署下 Rate Limit 與 Semaphore 的跨實例一致性
- **JVM GC 壓力模擬**：分別製造短命小物件、中等存活物件（快取場景）、大物件（humongous allocation），觀察不同 GC 策略的表現
- **Redis 效能基準**：涵蓋單筆 RTT、循序讀寫吞吐量、Pipeline Batch 效率、並發混合讀寫，提供可重複執行的量測資料
- **Redis OOM + Sentinel Failover**：在 `noeviction` 策略下觸發 OOM，並以 Lua 忙等佔用 event loop，驗證 Sentinel 的 failover 偵測與切換流程
- **即時效能監控**：透過 Micrometer + Prometheus + Grafana，觀察 HTTP 延遲分佈、錯誤率與 JVM 指標

---

## 技術棧

- **Java 21 / Spring Boot 3.3.3**，Servlet 容器替換為 Undertow
- **Redisson 4.1.0**：RRateLimiter、RSemaphore、RBatch、Lua Script
- **Micrometer + Prometheus**：HTTP latency histogram 及 JVM 指標，透過 `/actuator/prometheus` 暴露
- **Grafana**：連接 Prometheus 進行視覺化
- **Docker Compose**：本地一鍵啟動 Redis + Prometheus + Grafana
- **Kubernetes**：正式部署環境，Redis 使用 StatefulSet + Sentinel Sidecar（3 節點），監控堆疊部署於同一 namespace

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
