# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

**QPS-tuning** is a Spring Boot 3.3.3 application (Java 21) designed for testing and tuning QPS (queries per second) performance through distributed rate limiting, concurrency control, and GC load simulation. The project uses Redis via Redisson for distributed synchronization across multiple instances.

## Architecture

### Core Components

**Filters** (Order-based chain):
- `OrderRateLimitFilter` (Order=10): Global rate limiting for `/order` endpoint using Redisson's RRateLimiter. Configured for 100 requests/second across all instances. Returns HTTP 429 when exceeded.
- `OrderConcurrencyLimitFilter` (Order=20): Concurrent request limiting using Redisson's RSemaphore. Limits simultaneous requests to 100 across all instances. Returns HTTP 503 when exceeded.

**Controllers**:
- `OrderController` (`/order` GET): Simulates an order operation with 500ms processing delay and atomic request counter.
- `TuningController` (`/api/v1/tuning/try-get` GET): Testing endpoint with 1-second sleep.
- `GcLoadController` (`/gc/*`): Generates configurable GC pressure through three test cases:
  - `/gc/alloc`: Short-lived small objects (byte arrays, Maps, Lists)
  - `/gc/cache` (POST): Medium-lived objects stored in a ConcurrentHashMap with TTL
  - `/gc/cache/cleanup` (DELETE): Manual cleanup of expired cache entries
  - `/gc/big`: Large allocation test (humongous objects)

### Technology Stack
- **Framework**: Spring Boot 3.3.3 with Undertow (not Tomcat) as servlet container
- **Distributed Coordination**: Redisson 4.1.0 for Redis-based rate limiting and semaphores
- **Logging**: SLF4J with Lombok (@Slf4j)
- **Java**: Java 21 with Lombok for boilerplate reduction
- **Database**: No database (DataSourceAutoConfiguration excluded)

### Infrastructure
- **Local Development**: Docker Compose with Redis 7 Alpine in `/docker/docker-compose.redis.yaml`
- **Kubernetes Deployment**: Redis cluster with sentinel support in `/kubernates/` (3 nodes + 3 sentinels) targeting `front-mpos` namespace
- **Configuration**: Redis address configured via `REDIS_ADDRESS` environment variable (defaults to `redis://127.0.0.1:6379`)

## Building and Running

### Prerequisites
- Java 21
- Maven 3.9+ (mvnw provided)
- Docker & Docker Compose (for local Redis)

### Build
```bash
./mvnw clean package
```

### Run (Local Development)
1. Start Redis in Docker:
   ```bash
   cd docker
   docker-compose -f docker-compose.redis.yaml up -d
   ```

2. Start the application:
   ```bash
   ./mvnw spring-boot:run
   ```

The application starts on the default Spring Boot port (8080) and connects to Redis at `127.0.0.1:6379`.

### Run (Docker Image)
```bash
# Build OCI image
./mvnw spring-boot:build-image

# Override Redis sentinel address for container/k8s networking
docker run -e REDIS_SENTINEL_ADDRESS=redis://mps-redis-sentinel-service:26379 <image-name>
```

### Testing
```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=QpsTuningApplicationTests
```

Current test suite is minimal (only context load test).

## API Testing

### Rate Limiting Test
```bash
# Should succeed (within 100/sec limit)
curl http://localhost:8080/order

# Rapid requests to trigger rate limit (HTTP 429)
for i in {1..150}; do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/order; done
```

### GC Load Testing
```bash
# Small object allocation (20MB default)
curl "http://localhost:8080/gc/alloc?mb=50&chunk=1024&lists=5000"

# Cache put (15s TTL, 256KB payload, 200 entries)
curl -X POST "http://localhost:8080/gc/cache?ttlSeconds=15&payloadKb=256&entries=200"

# Cache cleanup
curl -X DELETE "http://localhost:8080/gc/cache/cleanup"

# Large allocations (4MB x 3)
curl "http://localhost:8080/gc/big?mb=4&repeat=3"
```

## Code Organization

```
src/main/java/tw/com/aidenmade/qpstuning/
├── QpsTuningApplication.java          # Boot entry point, excludes DataSource
├── api/
│   ├── OrderController.java           # Order endpoint with rate/concurrency limits
│   ├── TuningController.java          # Testing endpoint
│   └── GcLoadController.java          # GC pressure simulation
└── filter/
    ├── OrderRateLimitFilter.java      # Global rate limiter (RRateLimiter)
    └── OrderConcurrencyLimitFilter.java # Global concurrency limiter (RSemaphore)

src/main/resources/
└── application.yaml                   # Spring config + Redisson Redis connection
```

## Configuration

### application.yaml
- Spring application name: `QPS-tuning`
- Redisson sentinel config: connects to Redis Sentinel via `REDIS_SENTINEL_ADDRESS` env var (default `redis://127.0.0.1:26379`) and `REDIS_MASTER_NAME` env var (default `mymaster`)
- Virtual threads are commented out (can be enabled with `spring.threads.virtual.enabled: true`)

### Filter Order
Filters run in order (lowest @Order value first):
1. OrderRateLimitFilter (Order=10) - checks rate limit first
2. OrderConcurrencyLimitFilter (Order=20) - then checks concurrency

## Common Development Tasks

### Adding a New Performance Test Endpoint
Add a method to `GcLoadController` with query parameters for tuning. Validate parameter ranges to prevent resource exhaustion. Return a JSON response with test metadata.

### Modifying Limits
- Rate limit: Change `limiter.setRate(RateType.OVERALL, ...)` in `OrderRateLimitFilter` constructor
- Concurrency limit: Change `semaphore.trySetPermits(...)` in `OrderConcurrencyLimitFilter` constructor

### Kubernetes Deployment
Deploy using the YAML files in `/kubernates/`. The namespace `front-mpos` must exist. Redis services use cluster mode with sentinel for failover. Update `REDIS_ADDRESS` environment variable in your deployment manifest to point to the sentinel service.

## Key Dependencies
- spring-boot-starter-web (Undertow)
- spring-boot-starter-websocket (included but may be unused)
- spring-boot-starter-actuator (metrics/health endpoints)
- redisson-spring-boot-starter 4.1.0 (distributed coordination)
- lombok (compile-time annotation processing)
- spring-boot-starter-test (JUnit 5, Mockito)

## Git Branches
- `master` - stable release branch
- `develop` - active development branch (current)

Recent history focuses on adding rate/concurrency limiting, GC load tests, and Kubernetes Redis cluster deployment.
