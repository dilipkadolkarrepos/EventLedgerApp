# Event Ledger

A Spring Boot REST API for recording and querying financial transaction events with idempotent ingestion, out-of-order tolerance, and real-time balance computation.

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

**H2 console** (in-memory database browser):

```
http://localhost:8080/ledger/h2-console
```

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

---

### POST /events

Records a new transaction event. Submitting the same `eventId` more than once is safe — the original record is returned without a second write.

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
| `200 OK` | Duplicate `eventId` — original event returned, no write performed. |
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

Returns all events for an account sorted by `eventTimestamp` ascending.

```
GET /events?account=acc-xyz-999
```

Events that arrive out of chronological order (delayed producers, retries) are transparently reordered — the sort key is the business-supplied `eventTimestamp`, not the time the record was received.

**Response body (200 OK)** — array of event objects in the same shape as the single-event response above, ordered oldest-first by `eventTimestamp`.

Returns an empty array `[]` when the account has no events (not a 404).

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

`currency` is taken from the account's earliest event. The balance can be negative when debits exceed credits.

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

`details` is populated for validation errors; it is an empty array for all other error types.

---

## Design notes

### Idempotency

Each event carries a caller-supplied `eventId` which maps to a `UNIQUE` column in the database. On every ingest request the service queries `findByEventId` first. If a record is found it is returned immediately — `save()` is never called. This ensures exactly-once persistence even when a producer retries a request after a timeout.

The database-level unique constraint acts as the final safety net: under concurrent duplicate requests the second writer receives a constraint-violation exception rather than silently creating a duplicate row.

### Out-of-order tolerance

Events are stored with their business-supplied `eventTimestamp` exactly as provided. No ordering is imposed at write time. When events are queried, the repository sorts by `eventTimestamp ASC` at the database level. An event that arrives days late is automatically placed in the correct chronological position without any backfill or reprocessing.

### Balance computation

The balance is calculated in a single SQL aggregate query:

```sql
SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0)
FROM transaction_events
WHERE account_id = :accountId
```

No rows are loaded into the JVM. The database engine performs the arithmetic and returns one numeric value, keeping memory usage constant regardless of how many events an account has.
