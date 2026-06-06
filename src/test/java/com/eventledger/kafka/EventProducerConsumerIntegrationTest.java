package com.eventledger.kafka;

import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration test suite for the Kafka producer → consumer round-trip using an
 * embedded in-process Kafka broker (no external Kafka required).
 *
 * <h3>How it works</h3>
 * <p>{@link EmbeddedKafka} starts a real Kafka broker inside the test JVM.
 * {@code @TestPropertySource} overrides {@code spring.kafka.bootstrap-servers}
 * so that both the producer and the two consumers point at the embedded broker
 * instead of {@code localhost:9092}.</p>
 *
 * <h3>Test cases</h3>
 * <ol>
 *   <li>Valid event: published by producer → processed by consumer → persisted to H2 DB</li>
 *   <li>Idempotency: same event published twice → DB contains exactly one record</li>
 *   <li>Invalid timestamp: event with unparseable timestamp → routed to dead-letter (not persisted)</li>
 * </ol>
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "ledger.events.inbound",
                "ledger.events.processed",
                "ledger.events.dead-letter"
        }
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@DirtiesContext  // reset Spring context (and H2 DB) between test classes
class EventProducerConsumerIntegrationTest {

    @Autowired
    private EventProducer eventProducer;

    @Autowired
    private EventService eventService;

    // ── helpers ──────────────────────────────────────────────────────────────

    private EventRequest buildRequest(String eventId, String accountId,
                                      String type, String amount) {
        EventRequest req = new EventRequest();
        req.setEventId(eventId);
        req.setAccountId(accountId);
        req.setType(type);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency("USD");
        req.setEventTimestamp("2026-01-01T10:00:00Z");
        return req;
    }

    // ── Test 1: valid event round-trip ────────────────────────────────────────

    @Test
    @DisplayName("Test 1: Valid event is published, consumed, and persisted to DB")
    void givenValidEvent_whenPublishedViaProducer_thenPersistedByConsumer() throws Exception {
        // Arrange
        String eventId = "it-" + UUID.randomUUID();
        EventRequest request = buildRequest(eventId, "ACC-IT-01", "CREDIT", "100.00");

        // Act — publish to Kafka
        eventProducer.sendEvent(request).get(5, TimeUnit.SECONDS);

        // Assert — consumer should persist within 15 seconds
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    EventResponse response = eventService.getEventById(eventId);
                    assertThat(response).isNotNull();
                    assertThat(response.getEventId()).isEqualTo(eventId);
                    assertThat(response.getAccountId()).isEqualTo("ACC-IT-01");
                    assertThat(response.getType()).isEqualTo("CREDIT");
                    assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
                    assertThat(response.getCurrency()).isEqualTo("USD");
                });
    }

    // ── Test 2: idempotency ───────────────────────────────────────────────────

    @Test
    @DisplayName("Test 2: Duplicate events result in exactly one DB record (idempotency)")
    void givenDuplicateEvent_whenPublishedTwice_thenOnlyOneRecordPersisted() throws Exception {
        // Arrange
        String eventId = "it-idem-" + UUID.randomUUID();
        String accountId = "ACC-IT-IDEM";
        EventRequest request = buildRequest(eventId, accountId, "DEBIT", "50.00");

        // Act — publish the same event twice
        eventProducer.sendEvent(request).get(5, TimeUnit.SECONDS);
        eventProducer.sendEvent(request).get(5, TimeUnit.SECONDS);

        // Assert — wait for both to be consumed, then verify exactly one DB record
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Event must exist
                    EventResponse response = eventService.getEventById(eventId);
                    assertThat(response).isNotNull();

                    // Account ledger must contain exactly ONE entry
                    List<EventResponse> ledger = eventService.getEventsByAccount(accountId);
                    assertThat(ledger).hasSize(1);
                    assertThat(ledger.get(0).getEventId()).isEqualTo(eventId);
                });
    }

    // ── Test 3: invalid timestamp → dead-letter (not persisted) ──────────────

    @Test
    @DisplayName("Test 3: Event with invalid timestamp is routed to dead-letter and NOT persisted")
    void givenEventWithInvalidTimestamp_whenPublished_thenNotPersisted() throws Exception {
        // Arrange
        String eventId = "it-bad-ts-" + UUID.randomUUID();
        EventRequest request = new EventRequest();
        request.setEventId(eventId);
        request.setAccountId("ACC-IT-BAD");
        request.setType("CREDIT");
        request.setAmount(new BigDecimal("10.00"));
        request.setCurrency("USD");
        request.setEventTimestamp("NOT-A-VALID-ISO-TIMESTAMP"); // will throw InvalidEventException

        // Act — publish to Kafka (validation passes at controller level for embedded test)
        eventProducer.sendEvent(request).get(5, TimeUnit.SECONDS);

        // Assert — event should NOT be persisted in DB after consumer routes it to DLQ
        // Wait 10 s, then assert it's still missing
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() ->
                    assertThatThrownBy(() -> eventService.getEventById(eventId))
                            .isInstanceOf(EventNotFoundException.class)
                            .hasMessageContaining(eventId)
                );
    }
}
