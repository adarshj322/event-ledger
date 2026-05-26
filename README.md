# Event Ledger API

A RESTful API for ingesting, storing, and querying financial transaction events with **idempotency**, **out-of-order tolerance**, and **real-time balance computation**.

Built with **Spring Boot 4**, **H2 in-memory database**, and **Java 17**.

---

## Prerequisites

- **Java 17+** (e.g., [Eclipse Temurin](https://adoptium.net/))
- **Maven** (included via Maven Wrapper — no separate install needed)
- **Docker** (optional, for containerized setup)

---

## Quick Start

### Run locally

```bash
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`.

### Run with Docker

```bash
docker compose up --build
```

---

## Run Tests

```bash
./mvnw test
```

**32 tests total** (17 unit + 15 integration):

| Suite | Type | Tests | Covers |
|-------|------|-------|--------|
| `EventServiceTest` | Unit (Mockito) | 10 | Ingestion, duplicate detection, race condition handling, event retrieval, event-not-found, paginated queries, balance computation |
| `DtoMappingTest` | Unit | 6 | `EventResponse.fromEntity` mapping, null metadata, `BalanceResponse` construction, `ErrorResponse` single/multi-detail |
| `EventledgerApplicationTests` | Unit | 1 | Spring context loads |
| `EventControllerIntegrationTest` | Integration | 15 | Full HTTP lifecycle: idempotency, out-of-order events, balance aggregation, validation errors, pagination |

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event by ID |
| `GET` | `/events?account={accountId}` | List events for an account (chronological order) |
| `GET` | `/accounts/{accountId}/balance` | Get computed balance for an account |

### Pagination

The event listing endpoint supports pagination:

```
GET /events?account=acct-123&page=0&size=20
```

### API Usage Examples

> Run the server first with `./mvnw spring-boot:run`, then execute these commands in a separate terminal.

#### 1. Submit a CREDIT event

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

#### 2. Submit a DEBIT event

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

#### 3. Test Idempotency — Resubmit the same event

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

Expected: **`200 OK`** (not 201). Returns the original event. No duplicate is created.

#### 4. Retrieve a single event by ID

```bash
curl -s http://localhost:8080/events/evt-001
```

Expected: **`200 OK`** with the event details.

#### 5. Retrieve a non-existent event

```bash
curl -s http://localhost:8080/events/evt-does-not-exist
```

Expected: **`404 Not Found`** with error message:
```json
{"details":[],"error": "Event not found with id: evt-does-not-exist"}
```

#### 6. List all events for an account (paginated, chronological)

```bash
curl -s "http://localhost:8080/events?account=acct-123&page=0&size=10"
```

Expected: **`200 OK`** with paginated response containing events sorted by `eventTimestamp`.

#### 7. Get account balance

```bash
curl -s http://localhost:8080/accounts/acct-123/balance
```

Expected: **`200 OK`**. After steps 1 and 2 above, balance = 1000 − 250 = **750.00**:
```json
{"accountId": "acct-123", "balance": 750.00, "currency": "USD"}
```

#### 8. Balance for unknown account

```bash
curl -s http://localhost:8080/accounts/acct-unknown/balance
```

Expected: **`200 OK`** with zero balance:
```json
{"accountId": "acct-unknown", "balance": 0, "currency": null}
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **`BigDecimal` for amounts** | Avoids floating-point errors inherent in `double` for financial calculations |
| **`eventId` as natural primary key** | Enforces uniqueness at the DB level — idempotency is guaranteed even under concurrent requests |
| **Check-then-save with constraint fallback** | Primary duplicate detection via `findById` check; `DataIntegrityViolationException` catch handles race conditions |
| **Balance as live aggregation query** | Always correct regardless of event arrival order — no cached/stale balance |
| **Metadata stored as text** | Flexible schema — avoids a separate table for arbitrary key-value pairs |
| **`201 Created` vs `200 OK` status** | Safe retries: Returns `201` for new events and `200` for duplicate IDs. Avoids throwing `4xx` errors which can cause false-alarm alerts on client batch jobs. |
| **Zero balance for empty accounts** | Implicit lifecycle: Since there is no separate "Account Registry" table, accounts exist implicitly. The sum of events for an unknown/new account is mathematically `0`, returning `200 OK` to simplify client integrations. |

### Implicit Account Lifecycle (Ledger-Centric Architecture)

This API adopts an *implicit lifecycle* for accounts, similar to how blockchain address management works (e.g., Bitcoin or Ethereum wallets). Instead of requiring explicit account registration via a separate endpoint, accounts are provisioned implicitly the moment they receive their first transaction event. Any query for a new or unknown account ID naturally returns a `200 OK` status with a balance of `0.00` because the sum of an empty set of transaction events is mathematically zero.

This ledger-centric architecture offers significant advantages:
* **Lock-Free Concurrency:** By relying on an append-only transaction ledger (`INSERT` operations only) and calculating balances dynamically, we avoid the database write-contention (row locking) that occurs when multiple concurrent transactions attempt to update a single static account balance row.
* **Auditability & Temporal Queries:** Since the balance is derived dynamically by calculating the net sum of all transaction records (`COALESCE(SUM(...), 0)`), we maintain a verifiable, tamper-evident audit trail. This enables querying historical balances at any specific point in time by simply filtering the event timestamps.
* **Decoupled System Design:** The ledger operates as a pure transaction engine, fully decoupled from account management logic, identity services, or metadata schemas, making it easily extendable to new business domains without schema changes.

---

## H2 Console

Available at `http://localhost:8080/h2-console` when running locally.

- **JDBC URL:** `jdbc:h2:mem:eventledger`
- **Username:** `sa`
- **Password:** *(empty)*

---

## Project Structure

```
src/main/java/com/mphasis/eventledger/
├── controller/
│   └── EventController.java        # REST endpoints
├── dto/
│   ├── BalanceResponse.java         # Balance response DTO
│   ├── ErrorResponse.java           # Standardized error body
│   ├── EventRequest.java            # Request DTO with validation
│   └── EventResponse.java           # Response DTO
├── exception/
│   ├── EventNotFoundException.java  # 404 exception
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice
├── model/
│   ├── Event.java                   # JPA entity
│   └── EventType.java               # CREDIT/DEBIT enum
├── repository/
│   └── EventRepository.java         # JPA repository + balance query
├── service/
│   └── EventService.java            # Business logic
└── EventledgerApplication.java      # Spring Boot entry point
```