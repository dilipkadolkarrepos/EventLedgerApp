package com.eventledger.kafka;

import com.eventledger.config.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Kafka consumer for the {@value KafkaTopics#DEAD_LETTER} topic.
 *
 * <p>Each failed event is logged at WARN level and stored in an in-memory ring
 * buffer (max {@value #MAX_ENTRIES} entries) for operational visibility via
 * the {@code GET /ledger/dead-letter/recent} endpoint.</p>
 *
 * <p><strong>Note:</strong> The in-memory store resets on application restart.
 * It is designed for immediate operational awareness only, NOT for durable
 * audit storage.  For persistence, add a dead-letter DB table or forward events
 * to an external alerting system.</p>
 */
@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /** Maximum number of recent DLQ entries held in memory. */
    static final int MAX_ENTRIES = 100;

    /** Ring buffer: oldest entry removed when capacity is exceeded. */
    private final Deque<Map<String, Object>> recentFailures = new ArrayDeque<>(MAX_ENTRIES);

    @KafkaListener(
            topics = KafkaTopics.DEAD_LETTER,
            groupId = "event-ledger-dlq-group",
            containerFactory = "dlqContainerFactory"
    )
    @SuppressWarnings("unchecked")
    public void consumeDeadLetter(ConsumerRecord<String, Object> record,
                                  Acknowledgment acknowledgment) {

        log.warn("Dead-letter event received: key={}, partition={}, offset={}",
                record.key(), record.partition(), record.offset());

        Object value = record.value();
        Map<String, Object> entry;
        if (value instanceof Map) {
            entry = (Map<String, Object>) value;
        } else {
            entry = Map.of(
                    "event_id", String.valueOf(record.key()),
                    "raw_value", String.valueOf(value)
            );
        }

        synchronized (recentFailures) {
            if (recentFailures.size() >= MAX_ENTRIES) {
                recentFailures.pollFirst(); // remove oldest
            }
            recentFailures.addLast(entry);
        }

        acknowledgment.acknowledge();
    }

    /**
     * Returns a snapshot of the most recently received dead-letter entries,
     * newest last.  Safe to call from multiple threads.
     */
    public List<Map<String, Object>> getRecentFailures() {
        synchronized (recentFailures) {
            return new ArrayList<>(recentFailures);
        }
    }

    /** Returns the current count of entries in the in-memory buffer. */
    public int getFailureCount() {
        synchronized (recentFailures) {
            return recentFailures.size();
        }
    }
}
