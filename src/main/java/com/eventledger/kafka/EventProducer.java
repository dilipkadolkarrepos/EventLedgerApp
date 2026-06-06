package com.eventledger.kafka;

import com.eventledger.config.KafkaTopics;
import com.eventledger.dto.EventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka producer responsible for publishing {@link EventRequest} messages to the
 * {@value KafkaTopics#INBOUND} topic.
 *
 * <h3>Key design decisions</h3>
 * <ul>
 *   <li><b>Message key = eventId</b>: Kafka routes messages with the same key to the same
 *       partition, guaranteeing that all events for a given {@code eventId} are consumed
 *       in order.  This also supports the idempotency check in the consumer.</li>
 *   <li><b>Non-blocking send</b>: {@link #sendEvent} returns a {@link CompletableFuture} so
 *       callers can choose to wait synchronously (with a timeout) or chain callbacks.</li>
 *   <li><b>No exception swallowing</b>: failures are logged and propagated via the future
 *       so the controller can return an appropriate HTTP error.</li>
 * </ul>
 */
@Component
public class EventProducer {

    private static final Logger log = LoggerFactory.getLogger(EventProducer.class);

    private final KafkaTemplate<String, EventRequest> kafkaTemplate;

    public EventProducer(KafkaTemplate<String, EventRequest> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publish an {@link EventRequest} to {@value KafkaTopics#INBOUND}.
     *
     * @param eventRequest the event to publish; must not be {@code null}
     * @return a future that completes with the {@link SendResult} on success or
     *         exceptionally on broker-level failure
     */
    public CompletableFuture<SendResult<String, EventRequest>> sendEvent(EventRequest eventRequest) {
        String eventId = eventRequest.getEventId();

        log.info("Publishing event to Kafka: eventId={}, accountId={}, type={}",
                eventId, eventRequest.getAccountId(), eventRequest.getType());

        CompletableFuture<SendResult<String, EventRequest>> future =
                kafkaTemplate.send(KafkaTopics.INBOUND, eventId, eventRequest);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.debug("Event published successfully: eventId={}, partition={}, offset={}",
                        eventId,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish event to Kafka: eventId={}", eventId, ex);
            }
        });

        return future;
    }
}
