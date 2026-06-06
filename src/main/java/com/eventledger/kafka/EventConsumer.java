package com.eventledger.kafka;

import com.eventledger.config.KafkaTopics;
import com.eventledger.dto.EventRequest;
import com.eventledger.exception.InvalidEventException;
import com.eventledger.service.EventService;
import com.eventledger.service.EventService.SubmitResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer that reads {@link EventRequest} messages from
 * {@value KafkaTopics#INBOUND} and delegates to {@link EventService} for validation
 * and persistence.
 *
 * <h3>Acknowledgment strategy</h3>
 * <p>{@code ack-mode=MANUAL_IMMEDIATE} is set in {@code application.properties}.
 * This means <em>no offset is committed automatically</em>; we call
 * {@link Acknowledgment#acknowledge()} explicitly after every message — even
 * on failure — to avoid an infinite retry loop on poison-pill messages.</p>
 *
 * <h3>Routing</h3>
 * <ul>
 *   <li>Success → offset committed, confirmation published to {@value KafkaTopics#PROCESSED}</li>
 *   <li>{@link InvalidEventException} (business rule failure, permanently invalid) →
 *       offset committed, event published to {@value KafkaTopics#DEAD_LETTER}</li>
 *   <li>Any other exception (transient / unexpected) →
 *       offset committed, event published to {@value KafkaTopics#DEAD_LETTER}</li>
 * </ul>
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final EventService eventService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EventConsumer(EventService eventService,
                         KafkaTemplate<String, Object> kafkaTemplate) {
        this.eventService = eventService;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Main listener — processes one message at a time from the inbound topic.
     *
     * @param record        the Kafka record; key = eventId, value = EventRequest JSON
     * @param acknowledgment manual acknowledgment handle
     */
    @KafkaListener(
            topics = KafkaTopics.INBOUND,
            groupId = "event-ledger-consumer-group"
    )
    public void consumeEvent(ConsumerRecord<String, EventRequest> record,
                             Acknowledgment acknowledgment) {

        EventRequest request = record.value();
        String eventId = request != null ? request.getEventId() : "unknown";

        log.info("Received event from Kafka: topic={}, partition={}, offset={}, eventId={}",
                record.topic(), record.partition(), record.offset(), eventId);

        try {
            SubmitResult result = eventService.submitEvent(request);

            // ── Success path ───────────────────────────────────────────────
            acknowledgment.acknowledge();
            log.info("Event processed successfully: eventId={}, isNew={}",
                    eventId, result.isNew());
            publishProcessed(eventId, result.isNew());

        } catch (InvalidEventException e) {

            // ── Business rule failure (permanently invalid — do NOT retry) ─
            log.warn("Business validation failed for event: eventId={}, reason={}", eventId, e.getMessage());
            acknowledgment.acknowledge();
            publishDeadLetter(record, "INVALID_EVENT", e.getMessage());

        } catch (Exception e) {

            // ── Unexpected / system error ──────────────────────────────────
            log.error("Unexpected error processing event: eventId={}", eventId, e);
            acknowledgment.acknowledge();
            publishDeadLetter(record, "PROCESSING_ERROR", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void publishProcessed(String eventId, boolean isNew) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("event_id", eventId);
            payload.put("is_new", isNew);
            payload.put("processed_at", Instant.now().toString());
            kafkaTemplate.send(KafkaTopics.PROCESSED, eventId, payload);
        } catch (Exception e) {
            log.warn("Could not publish to processed topic for eventId={}: {}", eventId, e.getMessage());
        }
    }

    private void publishDeadLetter(ConsumerRecord<String, EventRequest> original,
                                   String errorType, String errorMessage) {
        try {
            EventRequest req = original.value();
            Map<String, Object> payload = new HashMap<>();
            payload.put("event_id", original.key());
            payload.put("error_type", errorType);
            payload.put("error_message", errorMessage);
            payload.put("original_topic", original.topic());
            payload.put("original_partition", original.partition());
            payload.put("original_offset", original.offset());
            payload.put("original_payload", req);
            payload.put("failed_at", Instant.now().toString());
            kafkaTemplate.send(KafkaTopics.DEAD_LETTER, original.key(), payload);
            log.warn("Event routed to dead-letter: eventId={}, errorType={}", original.key(), errorType);
        } catch (Exception e) {
            log.error("CRITICAL: Could not publish to dead-letter topic for eventId={}: {}",
                    original.key(), e.getMessage());
        }
    }
}
