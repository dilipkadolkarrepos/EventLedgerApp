# Event Ledger

A Spring Boot REST API for recording and querying financial transaction events with idempotent ingestion, concurrent-duplicate safety, out-of-order tolerance, paginated retrieval, and real-time balance computation.

---

## Prerequisites

| Tool | Minimum version | Check |
|------|----------------|-------|
| Java | 17 | `java -version` |
| Maven | 3.6 | `mvn -version` |
| Docker | 24 (Compose V2) | `docker compose version` |

---

## Install dependencies

```bash
mvn dependency:resolve
```

---

## Start the application

```bash
mvn spring-boot:run
```

The server starts on **port 8080** under the context path `/ledger`.

---

## Developer URLs

| Tool | URL |
|------|-----|
| Swagger UI | `http://localhost:8080/ledger/swagger-ui/index.html` |
| OpenAPI JSON spec | `http://localhost:8080/ledger/api-docs` |
| H2 console | `http://localhost:8080/ledger/h2-console` |

### H2 console connection details

| Field | Value |
|-------|-------|
| JDBC URL | `jdbc:h2:mem:eventledger;DB_CLOSE_DELAY=-1` |
| User name | `sa` |
| Password | *(leave blank)* |

---

## Run the tests

```bash
mvn test
```

Surefire HTML and XML reports are written to `target/surefire-reports/`.

The test suite contains **39 tests** across two classes:

| Class | Count | Scope |
|-------|-------|-------|
| `EventControllerTest` | 32 | Full-stack integration — Spring context + H2 + MockMvc |
| `EventServiceTest` | 8 | Pure unit — mocked repository, real `ObjectMapper` |

Test isolation: `@BeforeEach` issues `DELETE FROM transaction_events` against the shared in-memory H2 instance, which is faster and more reliable than restarting the Spring context.

---

## Build a runnable JAR

```bash
mvn clean package
java -jar target/event-ledger-0.0.1-SNAPSHOT.jar
```

---

## Run with Docker

Requires [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose plugin).

```bash
docker compose up --build
```

The first run downloads base images and compiles the project inside the builder stage — subsequent runs reuse the cached dependency layer and are much faster.

To run in the background:

```bash
docker compose up --build -d
```

Stop and remove the container:

```bash
docker compose down
```

---

## API reference

All paths are relative to `http://localhost:8080/ledger`.  
Interactive documentation with a **Try it out** button is available at the Swagger UI URL above.

---

### POST /events

Records a new transaction event.

**Idempotency (serial):** Submitting the same `eventId` more than once is safe — the original record is returned without a second write.

**Idempotency (concurrent):** Simultaneous requests carrying the same `eventId` are also safe — the database `UNIQUE` constraint on `event_id` guarantees at most one row is written; any losing thread receives the same `200 OK` response as a serial duplicate.

**Request body**

```json
{
  "eventId":        "evt-abc-001",
  "accountId":      "acc-xyz-999",
  "type":           "CREDIT",
  "amount":         250.00,
  "currency":       "USD",
  "eventTimestamp": "2026-05-24T10:30:00Z",
  "metadata":       { "source": "payment-gateway", "ref": "TXN-99" }
}
```

| Field | Type | Rules |
|-------|------|-------|
| `eventId` | string | Required. Caller-assigned idempotency key. |
| `accountId` | string | Required. |
| `type` | string | Required. `CREDIT` or `DEBIT`. |
| `amount` | number | Required. Must be greater than zero. |
| `currency` | string | Required. |
| `eventTimestamp` | string | Required. ISO 8601 instant, e.g. `2026-05-24T10:30:00Z`. |
| `metadata` | object | Optional. Free-form JSON object. |

**Responses**

| Status | Meaning |
|--------|---------|
| `201 Created` | Event persisted for the first time. |
| `200 OK` | Duplicate `eventId` (serial or concurrent) — original event returned, no write performed. |
| `400 Bad Request` | Validation failed or `eventTimestamp` is not a valid ISO 8601 instant. Body contains a `details` array listing each constraint violation. |

---

### GET /events/{id}

Retrieves a single event by its business key.

```
GET /events/evt-abc-001
```

**Response body (200 OK)**

```json
{
  "event_id":        "evt-abc-001",
  "account_id":      "acc-xyz-999",
  "type":            "CREDIT",
  "amount":          250.0000,
  "currency":        "USD",
  "event_timestamp": "2026-05-24T10:30:00Z",
  "metadata":        "{\"source\":\"payment-gateway\",\"ref\":\"TXN-99\"}",
  "received_at":     "2026-05-24T10:30:01.123Z"
}
```

| Status | Meaning |
|--------|---------|
| `200 OK` | Event found. |
| `404 Not Found` | No event with that `eventId` exists. |

---

### GET /events?account={accountId}

Returns a paginated, chronologically ordered list of events for an account.

```
GET /events?account=acc-xyz-999
GET /events?account=acc-xyz-999&page=1&size=50
```

Events that arrive out of chronological order (delayed producers, retries) are transparently reordered — the sort key is the business-supplied `eventTimestamp`, not the ingestion time.

**Query parameters**

| Parameter | Default | Constraints | Description |
|-----------|---------|-------------|-------------|
| `account` | — | Required | Account identifier to filter by. |
| `page` | `0` | `>= 0` | Zero-based page index. |
| `size` | `20` | `1–100` | Number of events per page. |

**Response body (200 OK)**

```json
{
  "content": [
    {
      "event_id":        "evt-abc-001",
      "account_id":      "acc-xyz-999",
      "type":            "CREDIT",
      "amount":          250.0000,
      "currency":        "USD",
      "event_timestamp": "2026-05-24T10:30:00Z",
      "metadata":        null,
      "received_at":     "2026-05-24T10:30:01.123Z"
    }
  ],
  "page":           0,
  "size":           20,
  "total_elements": 1,
  "total_pages":    1,
  "last":           true
}
```

Returns `content: []` with accurate totals when the account has no events — not a 404.  
Returns `content: []` with accurate totals when `page` exceeds the last page — not an error.

| Status | Meaning |
|--------|---------|
| `200 OK` | Page returned (may be empty). |
| `400 Bad Request` | Invalid `page` or `size` value. |

---

### GET /accounts/{accountId}/balance

Computes the net balance for an account.

```
GET /accounts/acc-xyz-999/balance
```

**Balance formula**

```
balance = Σ CREDIT amounts − Σ DEBIT amounts
```

**Response body (200 OK)**

```json
{
  "account_id": "acc-xyz-999",
  "balance":    1500.0000,
  "currency":   "USD"
}
```

`currency` is taken from the account's earliest event by `eventTimestamp`. The balance can be negative when debits exceed credits.

| Status | Meaning |
|--------|---------|
| `200 OK` | Balance computed. |
| `404 Not Found` | No events exist for that `accountId`. |

---

## Error response shape

All error responses share the same structure:

```json
{
  "timestamp": "2026-05-24T10:31:00.000Z",
  "status":    400,
  "error":     "Validation failed",
  "message":   "One or more fields failed validation",
  "details":   [
    "amount must be greater than zero",
    "eventId must not be blank"
  ]
}
```

`details` is populated for validation errors (`400`); it is an empty array for all other error types.

---

## Design notes

### Idempotency

Each event carries a caller-supplied `eventId` which maps to a `UNIQUE` column in the database. On every ingest request the service queries `findByEventId` first. If a record is found it is returned immediately — `save()` is never called. This ensures exactly-once persistence even when a producer retries a request after a timeout.

### Concurrent-duplicate safety

The check-then-act pattern (`findByEventId` → `save`) has a race window: two threads can both see "not found" and both attempt to insert the same `eventId`. The database `UNIQUE` constraint is the final arbiter — the second writer receives a `DataIntegrityViolationException`. The service catches this, re-reads the winner's committed record, and returns it with `200 OK`, identical to a serial duplicate.

The `submitEvent` method intentionally has no `@Transactional` annotation. With an outer transaction, Hibernate marks the session *rollback-only* the moment the constraint fires, making a subsequent re-query impossible in the same session. Without an outer transaction, each repository call gets its own short-lived transaction; the failed save rolls back cleanly and the re-query opens a fresh read transaction.

### Out-of-order tolerance

Events are stored with their business-supplied `eventTimestamp` exactly as provided. No ordering is imposed at write time. When events are queried, the repository sorts by `eventTimestamp ASC` at the database level (aided by the composite index on `(account_id, event_timestamp)`). An event that arrives days late is automatically placed in the correct chronological position without any backfill or reprocessing.

### Balance computation

The balance is calculated in a single SQL aggregate query:

```sql
SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0)
FROM transaction_events
WHERE account_id = :accountId
```

No rows are loaded into the JVM. The database engine performs the arithmetic and returns one numeric value, keeping memory usage constant regardless of how many events an account has.

The account's currency is determined by a separate `LIMIT 1` query (`findFirstByAccountIdOrderByEventTimestampAsc`) — only one row is fetched regardless of account size.

### Pagination

The listing endpoint uses Spring Data `Pageable` with an explicit `Sort.by("eventTimestamp").ascending()`. The response is wrapped in a `PagedResponse<T>` DTO that exposes exactly the six fields a client needs to drive a paginator (`content`, `page`, `size`, `total_elements`, `total_pages`, `last`). Spring Data's own `Page` type is never exposed directly, preventing accidental coupling to its internal serialisation format.

A validation gate (`@Min` / `@Max` on the controller parameters, enforced by `@Validated`) rejects out-of-range `page` and `size` values before the service is reached. Violations produce the same `400` error shape as request-body validation errors.

### Database indexes

```sql
-- Single-column indexes
idx_te_account_id       ON (account_id)
idx_te_event_timestamp  ON (event_timestamp)

-- Composite index — covers the dominant query pattern:
--   WHERE account_id = ? ORDER BY event_timestamp ASC
-- The leading column satisfies the WHERE predicate; the trailing column
-- provides the sort order, eliminating a post-filter sort step.
idx_te_account_event_time ON (account_id, event_timestamp)
```
