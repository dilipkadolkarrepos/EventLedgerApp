package com.eventledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String EVENTS_URL   = "/events";
    private static final String ACCOUNTS_URL = "/accounts";
    private static final String ACCOUNT_ID   = "acc-001";

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid request body. Use remove() on the returned map to
     * simulate missing fields in validation tests.
     */
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

    /** POSTs to /events, asserts the given HTTP status, and returns ResultActions for chaining. */
    private ResultActions post(Map<String, Object> body, int expectedStatus) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    // ─────────────────────────────────────────────────────────
    // IDEMPOTENCY
    // ─────────────────────────────────────────────────────────

    /** First write is a creation (201); replaying the identical payload is a no-op (200). */
    @Test
    void firstSubmissionReturns201_duplicateReturns200() throws Exception {
        Map<String, Object> event = buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        post(event, 201);
        post(event, 200);
    }

    /** Submitting the same event three times must not inflate the balance. */
    @Test
    void duplicateEventDoesNotAlterBalance() throws Exception {
        Map<String, Object> event = buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        post(event, 201);
        post(event, 200);
        post(event, 200);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    /** Two distinct eventIds on the same account must both persist; balance = sum. */
    @Test
    void twoDistinctEventsAccumulateBalance() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-002", ACCOUNT_ID, "CREDIT",  50.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.0));
    }

    // ─────────────────────────────────────────────────────────
    // OUT-OF-ORDER ARRIVAL
    // ─────────────────────────────────────────────────────────

    /**
     * Events submitted in reverse chronological order must be returned sorted by
     * their business eventTimestamp, not by insertion (received_at) order.
     */
    @Test
    void eventsReturnedInBusinessTimestampOrder() throws Exception {
        post(buildEvent("evt-may3", ACCOUNT_ID, "CREDIT", 30.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-may1", ACCOUNT_ID, "CREDIT", 10.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-may2", ACCOUNT_ID, "CREDIT", 20.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(EVENTS_URL).param("account", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].event_id").value("evt-may1"))
                .andExpect(jsonPath("$[1].event_id").value("evt-may2"))
                .andExpect(jsonPath("$[2].event_id").value("evt-may3"));
    }

    /** Net balance must be arithmetically correct regardless of arrival order. */
    @Test
    void balanceCorrectForOutOfOrderArrivals() throws Exception {
        // DEBIT arrives first, two CREDITs arrive later and out of order
        post(buildEvent("evt-003", ACCOUNT_ID, "DEBIT",   30.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-002", ACCOUNT_ID, "CREDIT",  50.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(120.0));   // 100 + 50 − 30
    }

    // ─────────────────────────────────────────────────────────
    // BALANCE
    // ─────────────────────────────────────────────────────────

    /** Net balance = Σ CREDITs − Σ DEBITs across multiple events. */
    @Test
    void netBalanceIsCreditSumMinusDebitSum() throws Exception {
        post(buildEvent("evt-c1", ACCOUNT_ID, "CREDIT", 500.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-c2", ACCOUNT_ID, "CREDIT", 300.0, "2026-05-02T10:00:00Z"), 201);
        post(buildEvent("evt-d1", ACCOUNT_ID, "DEBIT",  200.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-d2", ACCOUNT_ID, "DEBIT",  150.0, "2026-05-04T10:00:00Z"), 201);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(450.0));   // 500 + 300 − 200 − 150
    }

    @Test
    void unknownAccountBalanceReturns404() throws Exception {
        mockMvc.perform(get(ACCOUNTS_URL + "/unknown-acc/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not found"));
    }

    /** Debits exceeding credits must yield a negative balance — no floor at zero. */
    @Test
    void balanceCanBeNegative() throws Exception {
        post(buildEvent("evt-c1", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-d1", ACCOUNT_ID, "DEBIT",  300.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-200.0));  // 100 − 300
    }

    // ─────────────────────────────────────────────────────────
    // VALIDATION
    // ─────────────────────────────────────────────────────────

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

    /** The {@code @Pattern} constraint message must tell the caller the accepted values. */
    @Test
    void invalidTypeReturns400WithCreditDebitHint() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "TRANSFER", 100.0, "2026-05-01T10:00:00Z"), 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]", containsString("CREDIT or DEBIT")));
    }

    /** A parseable-but-wrong timestamp passes @NotBlank; the service rejects it with an ISO 8601 hint. */
    @Test
    void invalidTimestampReturns400WithIso8601Hint() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "not-a-timestamp"), 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("ISO 8601")));
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

    // ─────────────────────────────────────────────────────────
    // GET ENDPOINTS
    // ─────────────────────────────────────────────────────────

    @Test
    void getEventByIdFound() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 201);

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

    /** Requesting events for an account that has never submitted any must return an empty array, not 404. */
    @Test
    void getEventsByAccountWithNoEventsReturnsEmptyArray() throws Exception {
        mockMvc.perform(get(EVENTS_URL).param("account", "acc-never-seen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", empty()));
    }
}
