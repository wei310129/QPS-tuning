# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**QPS-tuning** is a Spring Boot 3.3.3 application (Java 21) designed for testing and tuning backend performance. It covers distributed rate limiting, concurrency control, JVM GC load simulation, Redis throughput benchmarking, and Redis Sentinel failover testing. The project uses Redis via Redisson for distributed synchronization across multiple instances.

## Architecture

### Core Components

**Filters** (Order-based chain, applied to `/order` only):
- `OrderRateLimitFilter` (Order=10): Global rate limiting using Redisson's `RRateLimiter`. 100 req/s across all instances (OVERALL). Returns HTTP 429 + `Retry-After: 1` when exceeded.
- `OrderConcurrencyLimitFilter` (Order=20): Concurrent request limiting using Redisson's `RSemaphore`. 100 simultaneous requests across all instances. Returns HTTP 503 when exceeded.

**Controllers**:
- `OrderController` (`/order` GET): Simulates an order operation with 500ms processing delay and atomic request counter.
- `TuningController` (`/api/v1/tuning/try-get` GET): Testing endpoint with 1-second sleep.
- `GcLoadController` (`/gc/*`): Generates configurable GC pressure:
  - `/gc/alloc` GET: Short-lived small objects (byte arrays, Maps, Lists)
  - `/gc/cache` POST: Medium-lived objects stored in JVM `ConcurrentHashMap` with TTL
  - `/gc/cache/cleanup` DELETE: Manual cleanup of expired cache entries
  - `/gc/big` GET: Large allocation test (humongous objects for G1GC)
- `RedisLoadController` (`/redis/*`): Redis benchmark suite:
  - `/redis/ping` GET: Single SET/GET RTT baseline (Case A)
  - `/redis/write` GET: Sequential SET throughput (Case B)
  - `/redis/read` GET: Sequential GET throughput (Case C)
  - `/redis/batch` GET: Pipeline batch write efficiency (Case D)
  - `/redis/mixed` GET: Concurrent mixed read/write with configurable threads and read ratio (Case E)
  - `/redis/oom-and-failover` GET: Fill Redis to OOM then block event loop via Lua busy-loop to trigger Sentinel failover (Case F)
  - `/redis/oom-cleanup` DELETE: Delete bench:oom:* keys after failover completes (Case G)

**Config**:
- `RedissonConfig`: Builds `RedissonClient` based on `REDIS_MODE` env var — `single` (default) uses `useSingleServer`, `sentinel` uses `useSentinelServers` with comma-separated `REDIS_SENTINEL_ADDRESSES`.

### Technology Stack
- **Framework**: Spring Boot 3.3.3 with Undertow (not Tomcat) as servlet container
- **Distributed Coordination**: Redisson 4.1.0 — RRateLimiter, RSemaphore, RBatch, Lua Script
- **Metrics**: Micrometer + `micrometer-registry-prometheus`; exposed at `/actuator/prometheus` with HTTP latency histogram
- **Logging**: SLF4J with Lombok (`@Slf4j`)
- **Java**: Java 21 with Lombok for boilerplate reduction
- **Database**: No database (`DataSourceAutoConfiguration` excluded)

### Infrastructure

**Local Development** (`/docker/docker-compose.redis.yaml`):

| Service | Container | Host Port |
|---------|-----------|-----------|
| App (Spring Boot) | — | 8080 (run separately) |
| Redis 7 Alpine | `qps-redis` | 6379 |
| Prometheus | `qps-prometheus` | 9090 |
| Grafana | `qps-grafana` | 3000 |

Prometheus scrapes `host.docker.internal:8080/actuator/prometheus`. Grafana default credentials: admin / admin.

**Kubernetes** (`/kubernetes/`, namespace `front-mpos`):

| Element | Service Name | Type | Port |
|---------|-------------|------|------|
| App | `qps-tuning-service` | ClusterIP | 8080 |
| Redis nodes (headless) | `mps-redis-ha-headless` | Headless | 6379, 26379 |
| Sentinel entry point | `mps-redis-ha-sentinel-service` | ClusterIP | 26379 |
| Prometheus | `prometheus-service` | ClusterIP | 9090 |
| Grafana | `grafana-service` | ClusterIP | 3000 |

Redis is deployed as a 3-Pod StatefulSet (`mps-redis-ha`). Each Pod contains a Redis container + Sentinel sidecar. An `initContainer` dynamically resolves the current master via Sentinel before generating `redis.conf` and `sentinel.conf`. `maxmemory` is set to 64MB with `noeviction` policy (used for OOM testing).

## Building and Running

### Prerequisites
- Java 21
- Maven 3.9+ (mvnw provided)
- Docker & Docker Compose (for local stack)

### Build
```bash
./mvnw clean package
```

### Run (Local Development)
1. Start Redis + Prometheus + Grafana:
   ```bash
   cd docker
   docker compose -f docker-compose.redis.yaml up -d
   ```

2. Start the application:
   ```bash
   ./mvnw spring-boot:run
   ```

The application starts on port 8080 and connects to Redis at `127.0.0.1:6379` by default.

### Run (Docker Image)
```bash
./mvnw spring-boot:build-image
# image: qps-tuning:latest

docker run \
  -e REDIS_MODE=sentinel \
  -e REDIS_SENTINEL_ADDRESSES=redis://mps-redis-ha-sentinel-service:26379 \
  -e REDIS_MASTER_NAME=mymaster \
  qps-tuning:latest
```

### Testing
```bash
./mvnw test
```

Current test suite is minimal (only context load test).

## Configuration

### application.yaml
- Spring application name: `QPS-tuning`
- Redis mode controlled by `REDIS_MODE` env var (`single` | `sentinel`)
- Single mode address: `REDIS_ADDRESS` (default `redis://127.0.0.1:6379`)
- Sentinel: `REDIS_SENTINEL_ADDRESSES` (comma-separated, default `redis://127.0.0.1:26379`) + `REDIS_MASTER_NAME` (default `mymaster`)
- Actuator exposes: `health`, `info`, `prometheus`
- Virtual threads commented out — enable with `spring.threads.virtual.enabled: true`

### Filter Order
Filters execute in ascending `@Order` value, applied only to `GET /order`:
1. `OrderRateLimitFilter` (Order=10) — rate check
2. `OrderConcurrencyLimitFilter` (Order=20) — concurrency check

### Kubernetes ConfigMap (`app-configmap.yaml`)
```
REDIS_MODE=sentinel
REDIS_MASTER_NAME=mymaster
REDIS_SENTINEL_ADDRESSES=redis://mps-redis-ha-sentinel-service.front-mpos.svc.cluster.local:26379
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxDirectMemorySize=256m
```

## Common Development Tasks

### Adding a New Performance Test Endpoint
Add a method to the appropriate controller (`GcLoadController` for GC tests, `RedisLoadController` for Redis tests). Clamp all query parameters to safe ranges to prevent resource exhaustion. Return a JSON map with test metadata including a `case` identifier.

### Modifying Limits
- Rate limit: Change `current.setRate(RateType.OVERALL, ...)` in `OrderRateLimitFilter.getLimiter()`
- Concurrency limit: Change `current.trySetPermits(...)` in `OrderConcurrencyLimitFilter.getSemaphore()`

### Kubernetes Deployment
```bash
kubectl apply -k kubernetes/
```
All resources land in namespace `front-mpos`. Access monitoring via port-forward:
```bash
kubectl port-forward -n front-mpos svc/prometheus-service 9090:9090
kubectl port-forward -n front-mpos svc/grafana-service 3000:3000
```

## Code Organization

```
src/main/java/tw/com/aidenmade/qpstuning/
├── QpsTuningApplication.java               # Boot entry, excludes DataSourceAutoConfiguration
├── api/
│   ├── OrderController.java                # /order, 500ms delay, atomic counter
│   ├── TuningController.java               # /api/v1/tuning/try-get, 1s sleep
│   ├── GcLoadController.java               # /gc/alloc, /gc/cache, /gc/big
│   └── RedisLoadController.java            # /redis/* benchmark suite (Cases A–G)
├── config/
│   └── RedissonConfig.java                 # Single / Sentinel mode switching
└── filter/
    ├── OrderRateLimitFilter.java           # @Order(10) RRateLimiter 100 req/s
    └── OrderConcurrencyLimitFilter.java    # @Order(20) RSemaphore 100 concurrent

src/main/resources/
└── application.yaml

docker/
├── docker-compose.redis.yaml               # Redis + Prometheus + Grafana
└── prometheus/prometheus.yml

kubernetes/
├── namespace.yaml
├── app-configmap.yaml / app-deployment.yaml / app-service.yaml
├── redis-configmap.yaml / redis-deployment.yaml / redis-service.yaml
├── monitoring-prometheus-configmap.yaml
├── monitoring-prometheus-deployment.yaml
├── monitoring-grafana-deployment.yaml
└── kustomization.yaml
```

## Key Dependencies
- `spring-boot-starter-web` (Undertow, Tomcat excluded)
- `spring-boot-starter-actuator` (health, prometheus endpoints)
- `redisson-spring-boot-starter` 4.1.0
- `micrometer-registry-prometheus`
- `lombok`
- `spring-boot-starter-test`

## Git Branches
- `master` — stable release branch
- `develop` — active development branch (current)
