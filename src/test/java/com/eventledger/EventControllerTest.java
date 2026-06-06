package com.eventledger;

import com.eventledger.dto.EventRequest;
import com.eventledger.kafka.EventProducer;
import com.eventledger.service.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for the event-driven EventController.
 *
 * <h3>Architecture note (updated for Kafka)</h3>
 * <p>POST /events now publishes to Kafka and returns {@code 202 Accepted}.
 * Persistence is handled asynchronously by the Kafka consumer.
 * To keep these tests fast and deterministic, {@link EventProducer} is mocked
 * with {@code @MockBean} and configured to call {@link EventService#submitEvent}
 * synchronously on each invocation — simulating immediate processing so that
 * balance and GET assertions work without async waiting.</p>
 *
 * <h3>What changed vs. the original tests</h3>
 * <ul>
 *   <li>All POST assertions now expect {@code 202} (not {@code 201} or {@code 200})</li>
 *   <li>Timestamp format validation no longer returns 400 from the controller;
 *       it is handled asynchronously in the consumer (→ DLQ)</li>
 *   <li>{@code EventProducer} is mocked; Kafka infrastructure is NOT required</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventService eventService;

    /**
     * Mock the Kafka producer so tests do not need a live broker.
     * The mock is configured in {@link #setUp()} to call EventService synchronously
     * for every sendEvent() call, making DB state immediately consistent.
     */
    @MockBean
    private EventProducer eventProducer;

    @BeforeEach
    void setUp() {
        // Clean DB before each test
        jdbcTemplate.execute("DELETE FROM transaction_events");

        // Configure mock: call EventService synchronously, then return a completed future.
        // Business errors (e.g. bad timestamps) are swallowed here — they would go to DLQ
        // in production but that is tested separately in EventProducerConsumerIntegrationTest.
        when(eventProducer.sendEvent(any(EventRequest.class))).thenAnswer(invocation -> {
            EventRequest req = invocation.getArgument(0);
            try {
                eventService.submitEvent(req);
            } catch (Exception ignored) {
                // Mirrors consumer DLQ routing: failures do not propagate to the caller
            }
            // Return a successfully completed future (broker ack simulated)
            CompletableFuture<SendResult<String, EventRequest>> future = new CompletableFuture<>();
            @SuppressWarnings("unchecked")
            SendResult<String, EventRequest> mockResult =
                    new SendResult<>(
                            new ProducerRecord<>("ledger.events.inbound", req.getEventId(), req),
                            new RecordMetadata(null, 0L, 0, 0L, 0, 0)
                    );
            future.complete(mockResult);
            return future;
        });
    }

    private static final String EVENTS_URL   = "/events";
    private static final String ACCOUNTS_URL = "/accounts";
    private static final String ACCOUNT_ID   = "acc-001";

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildEvent(String eventId, String accountId,
                                            String type, double amount, String timestamp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId",         eventId);
        body.put("accountId",       accountId);
        body.put("type",            type);
        body.put("amount",          amount);
        body.put("currency",        "USD");
        body.put("eventTimestamp",  timestamp);
        return body;
    }

    /**
     * POSTs to /events, asserts the given HTTP status, and returns ResultActions for chaining.
     * <p>NOTE: with the event-driven design, all successful submissions return {@code 202}.</p>
     */
    private ResultActions post(Map<String, Object> body, int expectedStatus) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    // ── IDEMPOTENCY ──────────────────────────────────────────────────────────

    /**
     * Both first and duplicate submissions return 202 Accepted.
     * The idempotency guard lives in EventService — only one record is persisted.
     */
    @Test
    void firstSubmissionAndDuplicateBothReturn202() throws Exception {
        Map<String, Object> event = buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        post(event, 202);
        post(event, 202);
    }

    /** Submitting the same event three times must not inflate the balance. */
    @Test
    void duplicateEventDoesNotAlterBalance() throws Exception {
        Map<String, Object> event = buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        post(event, 202);
        post(event, 202);
        post(event, 202);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    /** Two distinct eventIds on the same account must both persist; balance = sum. */
    @Test
    void twoDistinctEventsAccumulateBalance() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 202);
        post(buildEvent("evt-002", ACCOUNT_ID, "CREDIT",  50.0, "2026-05-02T10:00:00Z"), 202);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.0));
    }

    // ── OUT-OF-ORDER ARRIVAL ─────────────────────────────────────────────────

    @Test
    void eventsReturnedInBusinessTimestampOrder() throws Exception {
        post(buildEvent("evt-may3", ACCOUNT_ID, "CREDIT", 30.0, "2026-05-03T10:00:00Z"), 202);
        post(buildEvent("evt-may1", ACCOUNT_ID, "CREDIT", 10.0, "2026-05-01T10:00:00Z"), 202);
        post(buildEvent("evt-may2", ACCOUNT_ID, "CREDIT", 20.0, "2026-05-02T10:00:00Z"), 202);

        mockMvc.perform(get(EVENTS_URL).param("account", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].event_id").value("evt-may1"))
                .andExpect(jsonPath("$[1].event_id").value("evt-may2"))
                .andExpect(jsonPath("$[2].event_id").value("evt-may3"));
    }

    @Test
    void balanceCorrectForOutOfOrderArrivals() throws Exception {
        post(buildEvent("evt-003", ACCOUNT_ID, "DEBIT",   30.0, "2026-05-03T10:00:00Z"), 202);
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 202);
        post(buildEvent("evt-002", ACCOUNT_ID, "CREDIT",  50.0, "2026-05-02T10:00:00Z"), 202);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(120.0));
    }

    // ── BALANCE ──────────────────────────────────────────────────────────────

    @Test
    void netBalanceIsCreditSumMinusDebitSum() throws Exception {
        post(buildEvent("evt-c1", ACCOUNT_ID, "CREDIT", 500.0, "2026-05-01T10:00:00Z"), 202);
        post(buildEvent("evt-c2", ACCOUNT_ID, "CREDIT", 300.0, "2026-05-02T10:00:00Z"), 202);
        post(buildEvent("evt-d1", ACCOUNT_ID, "DEBIT",  200.0, "2026-05-03T10:00:00Z"), 202);
        post(buildEvent("evt-d2", ACCOUNT_ID, "DEBIT",  150.0, "2026-05-04T10:00:00Z"), 202);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(450.0));
    }

    @Test
    void unknownAccountBalanceReturns404() throws Exception {
        mockMvc.perform(get(ACCOUNTS_URL + "/unknown-acc/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not found"));
    }

    @Test
    void balanceCanBeNegative() throws Exception {
        post(buildEvent("evt-c1", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 202);
        post(buildEvent("evt-d1", ACCOUNT_ID, "DEBIT",  300.0, "2026-05-02T10:00:00Z"), 202);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-200.0));
    }

    // ── BEAN VALIDATION (controller layer, synchronous) ──────────────────────

    @Test
    void missingEventIdReturns400WithDetails() throws Exception {
        Map<String, Object> body = buildEvent(null, ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        body.remove("eventId");

        post(body, 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details", hasSize(1)));
    }

    @Test
    void zeroAmountReturns400() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 0.0, "2026-05-01T10:00:00Z"), 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", -1.0, "2026-05-01T10:00:00Z"), 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void invalidTypeReturns400WithCreditDebitHint() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "TRANSFER", 100.0, "2026-05-01T10:00:00Z"), 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]", containsString("CREDIT or DEBIT")));
    }

    /**
     * An invalid ISO-8601 timestamp passes @NotBlank and is accepted by the controller (202).
     * The format validation now happens asynchronously in the Kafka consumer and routes
     * the event to the dead-letter topic. See EventProducerConsumerIntegrationTest for
     * the full DLQ routing test.
     */
    @Test
    void invalidTimestampIsAcceptedByControllerAndRoutedToDlqAsynchronously() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "not-a-timestamp"), 202)
                .andExpect(jsonPath("$.event_id").value("evt-001"))
                .andExpect(jsonPath("$.message").value("Event received and queued for processing"));
    }

    @Test
    void missingAccountIdReturns400WithDetails() throws Exception {
        Map<String, Object> body = buildEvent("evt-001", null, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        body.remove("accountId");

        post(body, 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details", hasSize(1)));
    }

    // ── GET ENDPOINTS ─────────────────────────────────────────────────────────

    @Test
    void getEventByIdFound() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 202);

        mockMvc.perform(get(EVENTS_URL + "/evt-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value("evt-001"))
                .andExpect(jsonPath("$.account_id").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.type").value("CREDIT"));
    }

    @Test
    void getEventByIdNotFound() throws Exception {
        mockMvc.perform(get(EVENTS_URL + "/nonexistent-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not found"));
    }

    @Test
    void getEventsByAccountWithNoEventsReturnsEmptyArray() throws Exception {
        mockMvc.perform(get(EVENTS_URL).param("account", "acc-never-seen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", empty()));
    }
}
