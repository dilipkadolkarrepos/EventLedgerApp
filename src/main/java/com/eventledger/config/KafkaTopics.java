package com.eventledger.config;

/**
 * Central registry of Kafka topic name constants used by the EventLedger application.
 *
 * <p>Keeping topic names here (rather than as scattered string literals) means a rename
 * only requires a single change, and the compiler catches any misspelling at build time.</p>
 *
 * <p>This is a pure constants class — it cannot be instantiated or subclassed.</p>
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // utility class — no instances
    }

    /**
     * Receives raw {@code EventRequest} messages submitted via the REST API.
     * The Kafka producer publishes here; the EventConsumer reads from here.
     */
    public static final String INBOUND = "ledger.events.inbound";

    /**
     * Receives confirmation messages after an event has been successfully
     * validated and persisted to the database by the EventConsumer.
     */
    public static final String PROCESSED = "ledger.events.processed";

    /**
     * Receives events that could not be processed due to a business-rule violation
     * or an unexpected system error. Messages here are retained for 30 days for
     * operational investigation and potential replay.
     */
    public static final String DEAD_LETTER = "ledger.events.dead-letter";
}
