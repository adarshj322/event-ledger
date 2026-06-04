# Event Ledger

A distributed **Event Ledger** system composed of two microservices that process financial transaction events with **idempotency**, **out-of-order tolerance**, **real-time balance computation**, and **production-grade observability**.

Built with **Spring Boot 4**, **Java 17**, **Resilience4j**, and **H2** in-memory databases.

---

## Architecture

```
                          ┌──────────────────────┐
Browser / Client ──────→  │  Event Gateway API    │  :8080
                          │  (public-facing)      │
                          └──────┬───────────────┘
                                 │ REST (sync)
                                 │ + Circuit Breaker
                                 │ + Trace ID Propagation
                                 ▼
                          ┌──────────────────────┐
                          │  Account Service      │  :8081
                          │  (internal)           │
                          └──────────────────────┘
```

### Service Responsibilities

| Service | Port | Database | Responsibility |
|---------|------|----------|----------------|
| **Event Gateway** | 8080 | `jdbc:h2:mem:gateway` | Public API entry point. Validates input, stores events locally, forwards transactions to Account Service. Circuit breaker protects against Account Service failures. |
| **Account Service** | 8081 | `jdbc:h2:mem:accounts` | Internal service. Applies transactions, computes account balances, serves account details. Only called by the Gateway. |

Each service has its own **independent in-memory H2 database** — they do not share a database or any in-process state.

### Request Flow

1. **Client** sends `POST /events` to the **Gateway** (port 8080).
2. Gateway validates the request, stores the event in its **local database**, and forwards the transaction to the **Account Service** (port 8081) via a **circuit-breaker-protected** REST call.
3. Account Service applies the transaction to its database and computes the updated balance.
4. On `GET` requests for events, the Gateway serves directly from its **local database** — no dependency on the Account Service. This enables **graceful degradation**.
5. Balance queries (`GET /accounts/{id}/balance`) are **proxied** to the Account Service.

---

## Prerequisites

- **Java 17+** (e.g., [Eclipse Temurin](https://adoptium.net/))
- **Maven** (included via Maven Wrapper — no separate install needed)
- **Docker & Docker Compose** (optional, for containerized setup)

---

## Quick Start

### Option 1: Docker Compose (Recommended)

```bash
docker compose up --build
```

Both services start automatically. The Gateway API is available at `http://localhost:8080`.

### Option 2: Run Locally (two terminals)

```bash
# Terminal 1 — Account Service
./mvnw spring-boot:run -pl account-service

# Terminal 2 — Event Gateway
./mvnw spring-boot:run -pl event-gateway
```

The Gateway API is available at `http://localhost:8080`.  
The Account Service is available at `http://localhost:8081` (internal).

---

## Run Tests

```bash
./mvnw clean verify
```

> [!NOTE]
> **Why `clean verify` instead of `test`?**
> - **`clean`**: Deletes all stale compiled artifacts. Since the system was split from a monolith into a multi-module Maven project, cleaning ensures that old monolith classes do not contaminate the new build.
> - **`verify`**: Unlike `test` (which only runs unit tests), `verify` goes through the full lifecycle: compiling, running unit tests, packaging executable JARs, and executing the complete integration test suite. This guarantees the build is fully stable and ready for packaging.

**48 tests total** — all must pass:

| Test Class | Module | Tests | Type | Covers |
|-----------|--------|-------|------|--------|
| `AccountServiceTest` | account-service | 7 | Unit (Mockito) | Transaction application, idempotency, race condition handling, balance computation, account details |
| `AccountControllerIntegrationTest` | account-service | 11 | Integration (MockMvc) | Full HTTP lifecycle: transaction CRUD, duplicate detection, balance math, validation, health, trace ID |
| `EventServiceTest` | event-gateway | 7 | Unit (Mockito) | Event ingestion, duplicate detection, Account Service forwarding, event retrieval, pagination |
| `EventControllerIntegrationTest` | event-gateway | 15 | Integration (WireMock) | Full Gateway API: create/retrieve events, idempotency, out-of-order, validation, pagination, balance proxy, trace propagation |
| `ResiliencyIntegrationTest` | event-gateway | 7 | Integration (WireMock) | Circuit breaker: 503 on Account Service failure, graceful degradation for GET endpoints, health degradation |
| Context load tests | both | 2 | Smoke | Spring context loads correctly |

### What the Tests Verify

- ✅ **Idempotency** — duplicate `eventId` returns `200 OK`, not `201`, and does not alter balance
- ✅ **Out-of-order tolerance** — events arrive out of order but list in chronological order
- ✅ **Balance accuracy** — `CREDIT - DEBIT` math is correct, unaffected by duplicates
- ✅ **Validation** — missing fields → `400`, negative/zero amounts → `400`, unknown types → `400`
- ✅ **Resiliency** — Account Service down → Gateway returns `503` on POST and balance queries
- ✅ **Graceful degradation** — `GET /events/{id}` and `GET /events?account=...` still work when Account Service is down
- ✅ **Trace propagation** — `X-Trace-Id` is generated, echoed to client, and forwarded to Account Service
- ✅ **Health degradation** — health endpoint reports `DEGRADED` when Account Service is unavailable

---

## API Endpoints

### Event Gateway API (port 8080)

| Method | Endpoint | Description | Requires Account Service? |
|--------|----------|-------------|---------------------------|
| `POST` | `/events` | Submit a transaction event | ✅ Yes (forwards transaction) |
| `GET` | `/events/{id}` | Retrieve a single event by ID | ❌ No (local data) |
| `GET` | `/events?account={accountId}` | List events for an account (chronological) | ❌ No (local data) |
| `GET` | `/accounts/{accountId}/balance` | Get computed balance (proxied) | ✅ Yes |
| `GET` | `/health` | Health check with diagnostics | ❌ No |
| `GET` | `/metrics` | Custom request count metrics | ❌ No |

### Account Service API (port 8081, internal)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction to an account |
| `GET` | `/accounts/{accountId}/balance` | Get the current balance |
| `GET` | `/accounts/{accountId}` | Get account details + recent transactions |
| `GET` | `/health` | Health check |

### Pagination

The event listing endpoint supports pagination:

```
GET /events?account=acct-123&page=0&size=20
```

---

## API Usage Examples

> Start both services first (see [Quick Start](#quick-start)), then run these commands.

### 1. Submit a CREDIT event

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 1000.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z",
    "metadata": {
      "source": "mainframe-batch",
      "batchId": "B-9042"
    }
  }'
```

Expected: **`201 Created`** with the saved event in the response body.

### 2. Submit a DEBIT event

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-002",
    "accountId": "acct-123",
    "type": "DEBIT",
    "amount": 250.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T11:00:00Z"
  }'
```

Expected: **`201 Created`**.

### 3. Idempotency — Resubmit the same event

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 1000.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z"
  }'
```

Expected: **`200 OK`** (not `201`). Returns the original event. No duplicate is created, balance is unchanged.

### 4. Retrieve a single event

```bash
curl -s http://localhost:8080/events/evt-001
```

Expected: **`200 OK`** with the event details.

### 5. Retrieve a non-existent event

```bash
curl -s http://localhost:8080/events/evt-does-not-exist
```

Expected: **`404 Not Found`**:
```json
{"error": "Event not found with id: evt-does-not-exist", "details": []}
```

### 6. List events for an account (paginated, chronological)

```bash
curl -s "http://localhost:8080/events?account=acct-123&page=0&size=10"
```

Expected: **`200 OK`** with paginated response containing events sorted by `eventTimestamp`, regardless of arrival order.

### 7. Get account balance

```bash
curl -s http://localhost:8080/accounts/acct-123/balance
```

Expected: **`200 OK`**. After steps 1–2: balance = 1000 − 250 = **750.00**:
```json
{"accountId": "acct-123", "balance": 750.00, "currency": "USD"}
```

### 8. Health check

```bash
curl -s http://localhost:8080/health
```

Expected: **`200 OK`**:
```json
{
  "service": "event-gateway",
  "database": "UP",
  "accountService": "UP",
  "circuitBreaker": "CLOSED",
  "timestamp": "2026-05-15T10:00:00Z",
  "status": "UP"
}
```

### 9. Custom metrics

```bash
curl -s http://localhost:8080/metrics
```

Expected: **`200 OK`** with request count breakdowns:
```json
{
  "totalRequests": 5,
  "requestCounts": {
    "POST /events 201": 2,
    "GET /events/evt-001 200": 1,
    "GET /accounts/acct-123/balance 200": 1,
    "GET /health 200": 1
  }
}
```

### 10. Distributed tracing — pass a custom trace ID

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: my-custom-trace-123" \
  -d '{
    "eventId": "evt-003",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 500.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T12:00:00Z"
  }' -i 2>&1 | grep X-Trace-Id
```

Expected: response header `X-Trace-Id: my-custom-trace-123`. The same trace ID appears in both service logs.

---

## Resiliency: Circuit Breaker

### Why Circuit Breaker?

The Gateway uses a **Resilience4j circuit breaker** to wrap all outgoing calls to the Account Service. This pattern was chosen over retry-with-backoff because:

1. **Financial safety**: The `POST /accounts/{id}/transactions` endpoint mutates account state. Blind retries could cause duplicate balance mutations if the Account Service processed the request but the response was lost in transit. A circuit breaker **fails fast** instead of risking data corruption.

2. **Cascading failure prevention**: When the Account Service is down, a circuit breaker stops sending requests after a threshold, preventing the Gateway from exhausting its own thread pool with pending calls that will never succeed.

3. **Self-healing**: Once the Account Service recovers, the circuit breaker transitions through `HALF_OPEN` and automatically restores normal traffic flow without manual intervention.

### Circuit Breaker Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Sliding window size | 5 calls | Small window for fast detection in a low-traffic internal service |
| Failure rate threshold | 50% | Opens after 3 of 5 calls fail — balanced between sensitivity and noise |
| Wait duration in open state | 10 seconds | Quick recovery checks without overwhelming a recovering service |
| Permitted calls in half-open | 3 | Enough probes to confirm stability before fully closing |
| Slow call duration threshold | 3 seconds | Treats excessively slow responses as failures |
| Slow call rate threshold | 80% | Opens if most calls are unacceptably slow |

### Circuit Breaker States

| State | Behavior |
|-------|----------|
| **CLOSED** | Normal operation. All requests forwarded to Account Service. |
| **OPEN** | Account Service calls blocked. Gateway returns `503 Service Unavailable` immediately. |
| **HALF_OPEN** | Limited probe requests sent to test if Account Service has recovered. |

The current circuit breaker state is exposed via the `GET /health` endpoint.

---

## Graceful Degradation

When the Account Service is unavailable:

| Endpoint | Behavior | Reason |
|----------|----------|--------|
| `POST /events` | Returns **503 Service Unavailable** | Cannot apply transaction without Account Service |
| `GET /events/{id}` | **Works normally** ✅ | Served from Gateway's local database |
| `GET /events?account=...` | **Works normally** ✅ | Served from Gateway's local database |
| `GET /accounts/{id}/balance` | Returns **503 Service Unavailable** | Balance is computed by Account Service |
| `GET /health` | Returns **200** with `status: DEGRADED` | Reports degraded state, does not fail |

---

## Distributed Tracing

Trace IDs flow end-to-end across both services:

```
Client Request                    Event Gateway                    Account Service
─────────────                    ─────────────                    ───────────────
  │                                  │                                  │
  │──── X-Trace-Id: abc-123 ────────▶│                                  │
  │     (or auto-generated)          │                                  │
  │                                  │── X-Trace-Id: abc-123 ──────────▶│
  │                                  │   (propagated via header)        │
  │                                  │                                  │
  │◀──── X-Trace-Id: abc-123 ────────│◀── X-Trace-Id: abc-123 ─────────│
  │      (echoed in response)        │    (echoed in response)          │
```

### How It Works

1. **Gateway `TraceFilter`** reads the `X-Trace-Id` header from the incoming request. If absent, generates a new UUID.
2. The trace ID is stored in the **SLF4J MDC** (Mapped Diagnostic Context), so every log line during request processing includes it.
3. **`AccountServiceClient`** reads the trace ID from the MDC and forwards it to the Account Service via the `X-Trace-Id` HTTP header.
4. **Account Service `TraceFilter`** reads the incoming `X-Trace-Id` and puts it in its own MDC.
5. Both services echo the trace ID in the response headers for client-side correlation.

### Log Correlation Example

Both services log the same trace ID for a single request:

```
# Gateway log
{"traceId":"abc-123","service":"event-gateway","message":"Event created: eventId=evt-001"}

# Account Service log
{"traceId":"abc-123","service":"account-service","message":"Transaction applied: eventId=evt-001"}
```

---

## Structured Logging

Both services use [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder) for **JSON-formatted structured logging** in production.

### Log Format by Environment

| Profile | Format | Use Case |
|---------|--------|----------|
| `prod` or `docker` | **JSON** (structured) | Production, Docker Compose, log aggregation (ELK, Splunk) |
| Default (no profile) | **Plain text** | Local development, human readability |

### JSON Log Entry Example (production)

```json
{
  "timestamp": "2026-05-15T10:00:00.123Z",
  "level": "INFO",
  "logger_name": "c.m.e.g.controller.EventController",
  "message": "Event created: eventId=evt-001, accountId=acct-123, status=201 CREATED",
  "traceId": "abc-123",
  "service": "event-gateway"
}
```

### Plain Text Log Entry (development)

```
10:00:00.123 [http-nio-8080-exec-1] INFO  [abc-123] c.m.e.g.controller.EventController - Event created: eventId=evt-001
```

---

## Custom Metrics

The Gateway exposes custom request count metrics via `GET /metrics`:

- **Total request count** across all endpoints
- **Per-endpoint breakdown** by `METHOD /path STATUS_CODE`

This satisfies the observability requirement for at least one custom metric. Metrics are collected by `RequestMetricsFilter` and exposed by `MetricsController`.

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **`BigDecimal` for amounts** | Avoids floating-point errors inherent in `double` for financial calculations |
| **`eventId` as natural primary key** | Enforces uniqueness at the DB level — idempotency is guaranteed even under concurrent requests |
| **Check-then-save with constraint fallback** | Primary duplicate detection via `findById`; `DataIntegrityViolationException` catch handles race conditions between concurrent threads |
| **Balance as live aggregation query** | `COALESCE(SUM(...), 0)` — always correct regardless of event arrival order, no cached/stale state |
| **Gateway stores events locally** | Enables `GET` endpoints to work independently of the Account Service (graceful degradation) |
| **Circuit breaker over retry** | Retrying a financial POST is unsafe (risk of duplicate balance mutations). Circuit breaker fails fast and protects both services. |
| **`201 Created` vs `200 OK` for idempotency** | Returns `201` for new events and `200` for duplicates. Avoids `4xx` errors on safe retries. |
| **Zero balance for unknown accounts** | Implicit account lifecycle — accounts exist the moment they receive their first event. Sum of empty set is `0`. |
| **Metadata stored as text** | Flexible schema — avoids a separate table for arbitrary key-value pairs |
| **Programmatic Resilience4j** | The `resilience4j-spring-boot3` starter is incompatible with Spring Boot 4. Using the core `resilience4j-circuitbreaker` library directly with programmatic configuration ensures forward compatibility. |

---

## Project Structure

```
event-ledger/
├── pom.xml                          # Parent POM (Maven multi-module)
├── docker-compose.yml               # Orchestrates both services
├── account-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/.../account/
│       │   ├── AccountServiceApplication.java
│       │   ├── controller/
│       │   │   ├── AccountController.java       # POST transactions, GET balance, GET details
│       │   │   └── HealthController.java        # GET /health with DB diagnostics
│       │   ├── dto/
│       │   │   ├── AccountDetailsResponse.java
│       │   │   ├── BalanceResponse.java
│       │   │   ├── ErrorResponse.java
│       │   │   ├── TransactionRequest.java
│       │   │   └── TransactionResponse.java
│       │   ├── exception/
│       │   │   └── GlobalExceptionHandler.java  # Validation, malformed JSON, catch-all
│       │   ├── model/
│       │   │   ├── Transaction.java             # JPA entity (eventId = PK)
│       │   │   └── TransactionType.java         # CREDIT / DEBIT
│       │   ├── observability/
│       │   │   └── TraceFilter.java             # Reads X-Trace-Id → MDC
│       │   ├── repository/
│       │   │   └── TransactionRepository.java   # Balance aggregation query
│       │   └── service/
│       │       └── AccountService.java          # Idempotent apply, balance, details
│       ├── main/resources/
│       │   ├── application.properties
│       │   └── logback-spring.xml               # JSON (prod) / plain text (dev)
│       └── test/java/.../account/
│           ├── AccountServiceApplicationTests.java
│           ├── controller/
│           │   └── AccountControllerIntegrationTest.java
│           └── service/
│               └── AccountServiceTest.java
└── event-gateway/
    ├── pom.xml
    ├── Dockerfile
    └── src/
        ├── main/java/.../gateway/
        │   ├── GatewayApplication.java
        │   ├── config/
        │   │   └── RestClientConfig.java        # RestClient.Builder bean
        │   ├── controller/
        │   │   ├── EventController.java         # POST /events, GET /events, balance proxy
        │   │   ├── HealthController.java        # DB + Account Service + circuit breaker
        │   │   └── MetricsController.java       # GET /metrics
        │   ├── dto/
        │   │   ├── BalanceResponse.java
        │   │   ├── ErrorResponse.java
        │   │   ├── EventRequest.java
        │   │   └── EventResponse.java
        │   ├── exception/
        │   │   ├── AccountServiceUnavailableException.java  # → 503
        │   │   ├── EventNotFoundException.java              # → 404
        │   │   └── GlobalExceptionHandler.java
        │   ├── model/
        │   │   ├── Event.java                   # JPA entity (eventId = PK)
        │   │   └── EventType.java               # CREDIT / DEBIT
        │   ├── observability/
        │   │   ├── RequestMetricsFilter.java    # Custom metric: request counts
        │   │   └── TraceFilter.java             # Generates/reads X-Trace-Id → MDC
        │   ├── repository/
        │   │   └── EventRepository.java         # Chronological event listing
        │   └── service/
        │       ├── AccountServiceClient.java    # RestClient + Resilience4j circuit breaker
        │       └── EventService.java            # Ingest, forward, local retrieval
        ├── main/resources/
        │   ├── application.properties
        │   └── logback-spring.xml
        └── test/java/.../gateway/
            ├── GatewayApplicationTests.java
            ├── controller/
            │   ├── EventControllerIntegrationTest.java
            │   └── ResiliencyIntegrationTest.java
            └── service/
                └── EventServiceTest.java
```

---

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Java | 17 |
| Framework | Spring Boot | 4.0.6 |
| Build | Maven | Multi-module |
| Database | H2 (in-memory) | Per-service isolation |
| Resiliency | Resilience4j | 2.4.0 (core circuitbreaker) |
| Logging | Logstash Logback Encoder | 8.0 |
| Testing | JUnit 5, Mockito, WireMock | Spring Boot Test + WireMock Spring Boot 4.2.1 |
| Containerization | Docker, Docker Compose | Multi-stage builds |