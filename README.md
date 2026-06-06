# Event Ledger — Kafka Event-Driven Edition

A Spring Boot REST API for recording and querying financial transaction events with **idempotent ingestion**, **out-of-order tolerance**, and **real-time balance computation** — now powered by an **Apache Kafka event-driven architecture**.

Events submitted via REST are asynchronously processed through a Kafka pipeline before being persisted to the database. Read endpoints (GET) continue to serve data directly from the database with full synchronous consistency.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Kafka Installation & Setup](#kafka-installation--setup)
- [Run the Application](#run-the-application)
- [Run the Tests](#run-the-tests)
- [Build a Runnable JAR](#build-a-runnable-jar)
- [API Reference](#api-reference)
  - [POST /events](#post-events)
  - [GET /events/{id}](#get-eventsid)
  - [GET /events?account=](#get-eventsaccountaccountid)
  - [GET /accounts/{accountId}/balance](#get-accountsaccountidbalance)
  - [GET /dead-letter/recent](#get-dead-letterrecent)
  - [GET /dead-letter/count](#get-dead-lettercount)
  - [GET /actuator/health](#get-actuatorhealth)
- [Error Response Shape](#error-response-shape)
- [Kafka Topics](#kafka-topics)
- [Design Notes](#design-notes)
- [Project Structure](#project-structure)
- [Test Summary](#test-summary)

---

## Architecture Overview

```
REST POST /ledger/events
          │
          ▼  HTTP 202 Accepted (immediately)
    EventController
    (Bean Validation: @NotBlank, @Pattern, @NotNull)
          │
          ▼
    EventProducer ──────────► [ledger.events.inbound]  3 partitions · 7-day retention
                                         │
                                         ▼
                                 EventConsumer  (group: event-ledger-consumer-group)
                                         │
                            ┌────────────┴────────────────┐
                            ▼                             ▼
                     EventService                  InvalidEventException
                     .submitEvent()                or system error
                            │                             │
                     H2 Database                  [ledger.events.dead-letter]
                                                  1 partition · 30-day retention
                            │                             │
               [ledger.events.processed]           DeadLetterConsumer
               3 partitions · 7-day retention      (in-memory ring buffer)
                                                         │
REST GET /ledger/events/**    ◄── H2 DB       GET /ledger/dead-letter/recent
REST GET /ledger/accounts/**  ◄── H2 DB       GET /ledger/dead-letter/count
GET  /ledger/actuator/health
```

**Key principle:** Writes are asynchronous (Kafka); reads are synchronous (direct DB). This separates ingestion throughput from query latency.

---

## Prerequisites

| Tool | Minimum Version | Check Command |
|------|----------------|---------------|
| Java | 17 | `java -version` |
| Maven | 3.6 | `mvn -version` |
| Apache Kafka | 3.7.x | `kafka-topics.bat --version` |

> **Java 17 note:** Spring Boot 3.4.5 requires Java 17+. If `java -version` shows an older version, set `JAVA_HOME` to point to a Java 17 JDK before running Maven.

---

## Kafka Installation & Setup

### 1. Download Kafka

Download the latest Kafka 3.7.x binary from the Apache archive:

```
https://archive.apache.org/dist/kafka/3.7.1/kafka_2.13-3.7.1.tgz
```

Extract and place at `C:\kafka\kafka` (Windows) or `/opt/kafka` (Linux/macOS).

### 2. Create required data directories

```powershell
# Windows PowerShell
New-Item -ItemType Directory -Force "C:\kafka\data\zookeeper"
New-Item -ItemType Directory -Force "C:\kafka\data\kafka-logs"
```

```bash
# Linux / macOS
mkdir -p /opt/kafka/data/zookeeper /opt/kafka/data/kafka-logs
```

### 3. Configure Zookeeper

Edit `config/zookeeper.properties`:

```properties
dataDir=C:/kafka/data/zookeeper      # Windows
# dataDir=/opt/kafka/data/zookeeper  # Linux/macOS
clientPort=2181
maxClientCnxns=0
```

### 4. Configure Kafka broker

Edit `config/server.properties`:

```properties
broker.id=0
listeners=PLAINTEXT://localhost:9092
advertised.listeners=PLAINTEXT://localhost:9092
log.dirs=C:/kafka/data/kafka-logs    # Windows
# log.dirs=/opt/kafka/data/kafka-logs  # Linux/macOS
num.partitions=3
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
zookeeper.connect=localhost:2181
```

### 5. Start Zookeeper (keep terminal open)

```powershell
# Windows — Terminal 1
C:\kafka\kafka\bin\windows\zookeeper-server-start.bat C:\kafka\kafka\config\zookeeper.properties
```

```bash
# Linux / macOS — Terminal 1
/opt/kafka/bin/zookeeper-server-start.sh /opt/kafka/config/zookeeper.properties
```

Wait for: `binding to port 0.0.0.0/0.0.0.0:2181`

### 6. Start Kafka broker (keep terminal open)

```powershell
# Windows — Terminal 2
C:\kafka\kafka\bin\windows\kafka-server-start.bat C:\kafka\kafka\config\server.properties
```

```bash
# Linux / macOS — Terminal 2
/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
```

Wait for: `[KafkaServer id=0] started`

### 7. Create application topics

```powershell
# Windows
kafka-topics.bat --bootstrap-server localhost:9092 --create --topic ledger.events.inbound    --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=1048576
kafka-topics.bat --bootstrap-server localhost:9092 --create --topic ledger.events.processed  --partitions 3 --replication-factor 1 --config retention.ms=604800000
kafka-topics.bat --bootstrap-server localhost:9092 --create --topic ledger.events.dead-letter --partitions 1 --replication-factor 1 --config retention.ms=2592000000
```

```bash
# Linux / macOS
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic ledger.events.inbound    --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=1048576
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic ledger.events.processed  --partitions 3 --replication-factor 1 --config retention.ms=604800000
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic ledger.events.dead-letter --partitions 1 --replication-factor 1 --config retention.ms=2592000000
```

### 8. Verify topics

```powershell
kafka-topics.bat --bootstrap-server localhost:9092 --list
```

Expected output:
```
ledger.events.dead-letter
ledger.events.inbound
ledger.events.processed
```

---

## Run the Application

Ensure Zookeeper and Kafka broker are running first (see steps 5–6 above), then:

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

**Actuator health:**

```
http://localhost:8080/ledger/actuator/health
```

---

## Run the Tests

Tests use an **embedded in-process Kafka broker** — no external Kafka installation required.

```bash
mvn test
```

| Test Class | Tests | Description |
|---|---|---|
| `EventControllerTest` | 17 | Controller layer with mocked Kafka producer |
| `EventServiceTest` | 7 | Business logic unit tests |
| `EventProducerConsumerIntegrationTest` | 3 | Full Kafka round-trip with `@EmbeddedKafka` |
| **Total** | **27** | All passing · `BUILD SUCCESS` |

Surefire HTML and XML reports are written to `target/surefire-reports/`.

---

## Build a Runnable JAR

```bash
mvn clean package
java -jar target/event-ledger-0.0.1-SNAPSHOT.jar
```

---

## API Reference

All paths are relative to `http://localhost:8080/ledger`.

---

### POST /events

Accepts a transaction event and publishes it to Kafka for asynchronous processing and persistence. Submitting the same `eventId` more than once is safe — the duplicate is detected by the consumer and discarded without a second database write.

> **Architecture change:** This endpoint no longer returns the persisted event directly. It returns `202 Accepted` immediately after the Kafka broker acknowledges the message. Use `GET /events/{eventId}` to retrieve the persisted record after a short delay (typically < 1 second).

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
| `eventId` | string | Required. Caller-assigned idempotency key. Must be unique per event. |
| `accountId` | string | Required. |
| `type` | string | Required. `CREDIT` or `DEBIT`. |
| `amount` | number | Required. Must be greater than zero. |
| `currency` | string | Required. |
| `eventTimestamp` | string | Required. ISO 8601 instant, e.g. `2026-05-24T10:30:00Z`. |
| `metadata` | object | Optional. Free-form JSON object. |

**Response body (202 Accepted)**

```json
{
  "event_id": "evt-abc-001",
  "message":  "Event received and queued for processing"
}
```

**Responses**

| Status | Meaning |
|--------|---------|
| `202 Accepted` | Event published to Kafka. Persistence is in progress. |
| `400 Bad Request` | Bean validation failed (missing/invalid field). Body contains a `details` array. |
| `503 Service Unavailable` | Kafka broker unreachable or timed out after 5 seconds. Retry the request. |

> **Invalid `eventTimestamp` format:** A timestamp that passes `@NotBlank` but is not a valid ISO 8601 instant (e.g. `"not-a-date"`) is accepted by the controller with `202` and routed to the dead-letter topic by the consumer. Query `GET /dead-letter/recent` to inspect such failures.

---

### GET /events/{id}

Retrieves a single persisted event by its business key.

```
GET /events/evt-abc-001
```

> **Note:** After a `POST /events`, allow ~1 second for the Kafka consumer to persist the event before calling this endpoint.

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
| `404 Not Found` | No event with that `eventId` exists (or not yet processed by consumer). |

---

### GET /events?account={accountId}

Returns all events for an account sorted by `eventTimestamp` ascending.

```
GET /events?account=acc-xyz-999
```

Events that arrive out of chronological order (delayed producers, retries) are transparently reordered. The sort key is the business-supplied `eventTimestamp`, not the time the record was received.

**Response body (200 OK)** — array of event objects in the same shape as the single-event response above, ordered oldest-first by `eventTimestamp`.

Returns an empty array `[]` when the account has no events (not a 404).

---

### GET /accounts/{accountId}/balance

Computes the real-time net balance for an account.

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

### GET /dead-letter/recent

Returns up to 100 of the most recently failed events from the dead-letter topic. Entries are held in an in-memory ring buffer and reset on application restart.

```
GET /dead-letter/recent
```

**Response body (200 OK)**

```json
[
  {
    "event_id":          "evt-bad-001",
    "error_type":        "INVALID_EVENT",
    "error_message":     "eventTimestamp must be a valid ISO 8601 instant",
    "original_topic":    "ledger.events.inbound",
    "original_partition": 0,
    "original_offset":   5,
    "failed_at":         "2026-05-24T10:31:00Z"
  }
]
```

**Error types**

| `error_type` | Cause |
|---|---|
| `INVALID_EVENT` | Business rule violation (e.g. unparseable timestamp). Event is permanently invalid — will not be retried. |
| `PROCESSING_ERROR` | Unexpected system error in the consumer. Investigate and consider replaying. |

---

### GET /dead-letter/count

Returns the number of dead-letter entries currently in the in-memory buffer.

```
GET /dead-letter/count
```

**Response body (200 OK)**

```json
{ "count": 3 }
```

---

### GET /actuator/health

Spring Boot Actuator health endpoint. Reports the status of the database, disk space, and SSL.

```
GET /actuator/health
```

**Response body (200 OK)**

```json
{
  "status": "UP",
  "components": {
    "db":        { "status": "UP", "details": { "database": "H2" } },
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  }
}
```

---

## Error Response Shape

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

`details` is populated for bean validation errors (`400`). It is an empty array for all other error types (`404`, `503`).

---

## Kafka Topics

| Topic | Partitions | Retention | Purpose |
|-------|-----------|-----------|---------|
| `ledger.events.inbound` | 3 | 7 days | Raw `EventRequest` messages from the REST API |
| `ledger.events.processed` | 3 | 7 days | Confirmation payload after successful DB persistence |
| `ledger.events.dead-letter` | 1 | 30 days | Failed events for operational investigation and replay |

**Consumer groups**

| Group ID | Listens To | Purpose |
|---|---|---|
| `event-ledger-consumer-group` | `ledger.events.inbound` | Main event processing + DB persistence |
| `event-ledger-dlq-group` | `ledger.events.dead-letter` | Operational monitoring ring buffer |

---

## Design Notes

### Event-Driven Write Path

`POST /events` publishes to Kafka and returns `202 Accepted` as soon as the broker acknowledges the message (`acks=all`). The Kafka consumer then:

1. Deserialises the `EventRequest`
2. Calls `EventService.submitEvent()` (idempotency check + DB write)
3. On success → commits offset + publishes to `ledger.events.processed`
4. On `InvalidEventException` → commits offset + routes to `ledger.events.dead-letter`
5. On any other exception → commits offset + routes to `ledger.events.dead-letter`

Committing the offset on failure (rather than skipping it) prevents infinite retry loops on permanently invalid messages (poison pills).

### Synchronous Read Path

`GET` endpoints bypass Kafka entirely and query the H2 database directly via `EventService`. This keeps read latency low and independent of Kafka consumer lag.

### Idempotency

Each event carries a caller-supplied `eventId` mapped to a `UNIQUE` column in the database. Before every `save()` the consumer queries `findByEventId`. If a record is found it is returned immediately — no second write occurs. The database-level unique constraint acts as the final safety net under concurrent duplicates.

Additionally, Kafka message keys are set to `eventId`, ensuring all events for the same business key land on the same partition and are processed in order.

### Exactly-Once Producer Guarantees

The Kafka producer is configured with:

```properties
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.max.in.flight.requests.per.connection=1
```

This combination guarantees the broker receives each message at most once, even on retried sends.

### Out-of-Order Tolerance

Events are stored with their business-supplied `eventTimestamp` exactly as provided. No ordering is imposed at write time. Queries sort by `eventTimestamp ASC` at the database level. An event that arrives days late is automatically placed in the correct chronological position without any backfill or reprocessing.

### Balance Computation

The balance is calculated in a single SQL aggregate query — no rows are loaded into the JVM:

```sql
SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0)
FROM transaction_events
WHERE account_id = :accountId
```

---

## Project Structure

```
src/
├── main/java/com/eventledger/
│   ├── EventLedgerApplication.java          # Spring Boot entry point
│   ├── config/
│   │   ├── KafkaConfig.java                 # NewTopic beans + DLQ container factory
│   │   └── KafkaTopics.java                 # Topic name constants
│   ├── controller/
│   │   ├── AccountController.java           # GET /accounts/{id}/balance
│   │   ├── DeadLetterController.java        # GET /dead-letter/recent + /count
│   │   └── EventController.java             # POST /events (→ Kafka), GET /events/**
│   ├── dto/
│   │   ├── BalanceResponse.java
│   │   ├── ErrorResponse.java
│   │   ├── EventRequest.java                # Validated inbound payload
│   │   └── EventResponse.java
│   ├── exception/
│   │   ├── EventNotFoundException.java      # → 404
│   │   ├── GlobalExceptionHandler.java      # @RestControllerAdvice
│   │   └── InvalidEventException.java       # → 400 / DLQ
│   ├── kafka/
│   │   ├── DeadLetterConsumer.java          # Consumes ledger.events.dead-letter
│   │   ├── EventConsumer.java               # Consumes ledger.events.inbound
│   │   └── EventProducer.java               # Publishes to ledger.events.inbound
│   ├── model/
│   │   └── TransactionEvent.java            # JPA entity
│   ├── repository/
│   │   └── EventRepository.java             # JPA repository + custom JPQL
│   └── service/
│       └── EventService.java                # Business logic + idempotency
├── main/resources/
│   ├── application.properties               # Server, H2, Kafka, Actuator config
│   └── schema.sql                           # DDL for transaction_events table
└── test/java/com/eventledger/
    ├── EventControllerTest.java             # MockMvc + mocked EventProducer (17 tests)
    ├── EventServiceTest.java                # Unit tests for EventService (7 tests)
    └── kafka/
        └── EventProducerConsumerIntegrationTest.java  # @EmbeddedKafka (3 tests)
```

---

## Test Summary

| Test Class | Tests | Type | Kafka |
|---|---|---|---|
| `EventControllerTest` | 17 | Controller (MockMvc) | Mocked (`@MockBean EventProducer`) |
| `EventServiceTest` | 7 | Unit | None |
| `EventProducerConsumerIntegrationTest` | 3 | Integration | `@EmbeddedKafka` (no external broker) |
| **Total** | **27** | | |

All tests run with `mvn test` — no external Kafka required for the test suite.

### Integration test scenarios

| Test | Scenario | Validates |
|---|---|---|
| 1 | Valid event published → consumer persists to DB | Full round-trip |
| 2 | Same event published twice → exactly one DB record | Idempotency via Kafka |
| 3 | Invalid `eventTimestamp` published → event NOT in DB | DLQ routing |
