package com.eventledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class EventControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String EVENTS_URL   = "/events";
    private static final String ACCOUNTS_URL = "/accounts";
    private static final String ACCOUNT_ID   = "acc-001";

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM transaction_events");
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private Map<String, Object> buildEvent(String eventId, String accountId,
                                            String type, double amount, String timestamp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId",        eventId);
        body.put("accountId",      accountId);
        body.put("type",           type);
        body.put("amount",         amount);
        body.put("currency",       "USD");
        body.put("eventTimestamp", timestamp);
        return body;
    }

    private ResultActions post(Map<String, Object> body, int expectedStatus) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    // ─────────────────────────────────────────────────────────
    // IDEMPOTENCY
    // ─────────────────────────────────────────────────────────

    @Test
    void firstSubmissionReturns201_duplicateReturns200() throws Exception {
        Map<String, Object> event = buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z");
        post(event, 201);
        post(event, 200);
    }

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
     * The response is now paged — events live under {@code $.content}.
     */
    @Test
    void eventsReturnedInBusinessTimestampOrder() throws Exception {
        post(buildEvent("evt-may3", ACCOUNT_ID, "CREDIT", 30.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-may1", ACCOUNT_ID, "CREDIT", 10.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-may2", ACCOUNT_ID, "CREDIT", 20.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(EVENTS_URL).param("account", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].event_id").value("evt-may1"))
                .andExpect(jsonPath("$.content[1].event_id").value("evt-may2"))
                .andExpect(jsonPath("$.content[2].event_id").value("evt-may3"));
    }

    @Test
    void balanceCorrectForOutOfOrderArrivals() throws Exception {
        post(buildEvent("evt-003", ACCOUNT_ID, "DEBIT",   30.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-002", ACCOUNT_ID, "CREDIT",  50.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(120.0));
    }

    // ─────────────────────────────────────────────────────────
    // BALANCE
    // ─────────────────────────────────────────────────────────

    @Test
    void netBalanceIsCreditSumMinusDebitSum() throws Exception {
        post(buildEvent("evt-c1", ACCOUNT_ID, "CREDIT", 500.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-c2", ACCOUNT_ID, "CREDIT", 300.0, "2026-05-02T10:00:00Z"), 201);
        post(buildEvent("evt-d1", ACCOUNT_ID, "DEBIT",  200.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-d2", ACCOUNT_ID, "DEBIT",  150.0, "2026-05-04T10:00:00Z"), 201);

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
        post(buildEvent("evt-c1", ACCOUNT_ID, "CREDIT", 100.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-d1", ACCOUNT_ID, "DEBIT",  300.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(ACCOUNTS_URL + "/" + ACCOUNT_ID + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-200.0));
    }

    // ─────────────────────────────────────────────────────────
    // VALIDATION (request-body)
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
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", -1.0, "2026-05-01T10:00:00Z"), 400)
                .andExpect(jsonPath("$.status").value(400));
    }

    /** The {@code @Pattern} constraint message must tell the caller the accepted values. */
    @Test
    void invalidTypeReturns400WithCreditDebitHint() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "TRANSFER", 100.0, "2026-05-01T10:00:00Z"), 400)
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]", containsString("CREDIT or DEBIT")));
    }

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
    // GET — single event and empty listing
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

    /** An account with no events must return an empty page, not 404. */
    @Test
    void getEventsByAccountWithNoEventsReturnsEmptyPage() throws Exception {
        mockMvc.perform(get(EVENTS_URL).param("account", "acc-never-seen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.total_elements").value(0));
    }

    // ─────────────────────────────────────────────────────────
    // PAGINATION — correct slicing behaviour
    // ─────────────────────────────────────────────────────────

    /**
     * First page (page=0, size=2) of 5 events must return the two oldest events
     * in {@code eventTimestamp ASC} order.
     */
    @Test
    void paginationFirstPageReturnsOldestEventsInOrder() throws Exception {
        insertFiveOrderedEvents();

        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].event_id").value("evt-01"))
                .andExpect(jsonPath("$.content[1].event_id").value("evt-02"));
    }

    /**
     * Second page (page=1, size=2) must return the next two events, confirming
     * the database offset is applied correctly.
     */
    @Test
    void paginationSecondPageReturnsNextBatch() throws Exception {
        insertFiveOrderedEvents();

        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].event_id").value("evt-03"))
                .andExpect(jsonPath("$.content[1].event_id").value("evt-04"));
    }

    /**
     * Verifies all six paged-wrapper metadata fields for 5 events split into
     * pages of size 2: 3 pages total, page 0 is not the last.
     */
    @Test
    void paginationResponseMetadataIsCorrect() throws Exception {
        insertFiveOrderedEvents();

        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total_elements").value(5))
                .andExpect(jsonPath("$.total_pages").value(3))
                .andExpect(jsonPath("$.last").value(false));
    }

    /**
     * A page number beyond the last page must return empty content with accurate
     * totals — not an error.
     */
    @Test
    void paginationBeyondLastPageReturnsEmptyContent() throws Exception {
        post(buildEvent("evt-001", ACCOUNT_ID, "CREDIT", 10.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-002", ACCOUNT_ID, "CREDIT", 20.0, "2026-05-02T10:00:00Z"), 201);

        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("page", "99")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.total_elements").value(2));
    }

    // ─────────────────────────────────────────────────────────
    // PAGINATION — validation gate
    // ─────────────────────────────────────────────────────────

    /**
     * {@code page=-1} must be rejected before the service is reached.
     * The gate uses {@code @Min(0)} on the controller parameter; failures are
     * reported as 400 with the exact constraint message in {@code details}.
     */
    @Test
    void paginationNegativePageReturns400() throws Exception {
        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]", containsString("page must be >= 0")));
    }

    /**
     * {@code size=0} is meaningless for pagination and must be rejected by
     * the {@code @Min(1)} gate.
     */
    @Test
    void paginationZeroSizeReturns400() throws Exception {
        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]", containsString("size must be >= 1")));
    }

    /**
     * {@code size=101} exceeds the server-enforced maximum of 100 and must be
     * rejected by the {@code @Max(100)} gate before hitting the database.
     */
    @Test
    void paginationExcessiveSizeReturns400() throws Exception {
        mockMvc.perform(get(EVENTS_URL)
                        .param("account", ACCOUNT_ID)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]", containsString("size must be <= 100")));
    }

    // ─────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────

    /** Inserts 5 events with sequential timestamps; used by pagination tests. */
    private void insertFiveOrderedEvents() throws Exception {
        post(buildEvent("evt-01", ACCOUNT_ID, "CREDIT", 10.0, "2026-05-01T10:00:00Z"), 201);
        post(buildEvent("evt-02", ACCOUNT_ID, "CREDIT", 20.0, "2026-05-02T10:00:00Z"), 201);
        post(buildEvent("evt-03", ACCOUNT_ID, "CREDIT", 30.0, "2026-05-03T10:00:00Z"), 201);
        post(buildEvent("evt-04", ACCOUNT_ID, "CREDIT", 40.0, "2026-05-04T10:00:00Z"), 201);
        post(buildEvent("evt-05", ACCOUNT_ID, "CREDIT", 50.0, "2026-05-05T10:00:00Z"), 201);
    }
}
