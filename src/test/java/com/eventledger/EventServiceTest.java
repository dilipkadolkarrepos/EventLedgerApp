package com.eventledger;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.exception.InvalidEventException;
import com.eventledger.model.TransactionEvent;
import com.eventledger.repository.EventRepository;
import com.eventledger.service.EventService;
import com.eventledger.service.EventService.SubmitResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for EventService.
 * EventRepository is mocked; a real ObjectMapper is used to exercise the
 * metadata-serialisation path without Spring context overhead.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    // Use a real ObjectMapper — same behaviour as the Spring-managed bean for
    // plain Map → JSON serialisation, but without the application context cost.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, objectMapper);
    }

    // ─────────────────────────────────────────────────────────
    // submitEvent — idempotency
    // ─────────────────────────────────────────────────────────

    /**
     * When the business key already exists the service must return the stored
     * record with isNew=false and must never call save().
     */
    @Test
    void submitEvent_existingEventId_returnsIsNewFalseWithoutSave() {
        when(eventRepository.findByEventId("evt-001"))
                .thenReturn(Optional.of(sampleEvent("evt-001")));

        SubmitResult result = eventService.submitEvent(
                buildRequest("evt-001", "CREDIT", "100.00", "2026-05-01T10:00:00Z"));

        assertThat(result.isNew()).isFalse();
        assertThat(result.getResponse().getEventId()).isEqualTo("evt-001");
        verify(eventRepository, never()).save(any());
    }

    /**
     * A genuinely new eventId must be persisted exactly once and isNew=true.
     */
    @Test
    void submitEvent_newEventId_returnsIsNewTrueAndSavesOnce() {
        when(eventRepository.findByEventId("evt-002")).thenReturn(Optional.empty());
        when(eventRepository.save(any(TransactionEvent.class)))
                .thenReturn(sampleEvent("evt-002"));

        SubmitResult result = eventService.submitEvent(
                buildRequest("evt-002", "DEBIT", "250.00", "2026-05-02T10:00:00Z"));

        assertThat(result.isNew()).isTrue();
        assertThat(result.getResponse().getEventId()).isEqualTo("evt-002");
        verify(eventRepository).save(any(TransactionEvent.class));
    }

    /**
     * Simulates the race window: both threads pass the findByEventId check, then
     * the second thread's save() hits the UNIQUE constraint. The service must catch
     * DataIntegrityViolationException, re-read the winner's record, and return it
     * with isNew=false — indistinguishable from a serial duplicate to the caller.
     */
    @Test
    void submitEvent_concurrentDuplicate_catchesConstraintAndReturnsExisting() {
        TransactionEvent winner = sampleEvent("evt-race");

        // First call: fast-path check returns empty (both threads passed it).
        // Second call: after the constraint fires, re-query returns the winner's record.
        when(eventRepository.findByEventId("evt-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));

        // save() simulates the UNIQUE constraint violation thrown by the DB.
        when(eventRepository.save(any(TransactionEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint on event_id"));

        SubmitResult result = eventService.submitEvent(
                buildRequest("evt-race", "CREDIT", "100.00", "2026-05-01T10:00:00Z"));

        assertThat(result.isNew()).isFalse();
        assertThat(result.getResponse().getEventId()).isEqualTo("evt-race");
        // findByEventId called twice: once at the top (fast-path), once after catch (re-query).
        verify(eventRepository, times(2)).findByEventId("evt-race");
        // save attempted exactly once — the failed attempt is not retried.
        verify(eventRepository, times(1)).save(any(TransactionEvent.class));
    }

    // ─────────────────────────────────────────────────────────
    // submitEvent — timestamp parsing
    // ─────────────────────────────────────────────────────────

    /**
     * A non-ISO-8601 timestamp string must be rejected before any write attempt
     * with a message that tells the caller the expected format.
     */
    @Test
    void submitEvent_invalidTimestamp_throwsInvalidEventExceptionWithIso8601Hint() {
        when(eventRepository.findByEventId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                eventService.submitEvent(
                        buildRequest("evt-003", "CREDIT", "50.00", "not-a-timestamp")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("ISO 8601");
    }

    // ─────────────────────────────────────────────────────────
    // getBalance
    // ─────────────────────────────────────────────────────────

    /**
     * An accountId with no recorded events must surface as a 404-mapped exception,
     * not a NullPointerException or empty response.
     */
    @Test
    void getBalance_unknownAccount_throwsEventNotFoundException() {
        when(eventRepository.findFirstByAccountIdOrderByEventTimestampAsc("acc-unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getBalance("acc-unknown"))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining("acc-unknown");
    }

    /**
     * Balance and currency are derived from the repository and scaled to 4 d.p.
     * Currency is taken from the account's earliest event.
     */
    @Test
    void getBalance_knownAccount_returnsCorrectResponse() {
        when(eventRepository.findFirstByAccountIdOrderByEventTimestampAsc("acc-001"))
                .thenReturn(Optional.of(sampleEvent("evt-001")));
        when(eventRepository.computeBalanceByAccountId("acc-001"))
                .thenReturn(new BigDecimal("350.00"));

        BalanceResponse response = eventService.getBalance("acc-001");

        assertThat(response.getAccountId()).isEqualTo("acc-001");
        assertThat(response.getCurrency()).isEqualTo("USD");
        assertThat(response.getBalance()).isEqualByComparingTo(new BigDecimal("350.0000"));
    }

    // ─────────────────────────────────────────────────────────
    // getEventById
    // ─────────────────────────────────────────────────────────

    @Test
    void getEventById_notFound_throwsEventNotFoundException() {
        when(eventRepository.findByEventId("evt-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEventById("evt-missing"))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining("evt-missing");
    }

    @Test
    void getEventById_found_returnsCorrectEventResponse() {
        when(eventRepository.findByEventId("evt-found"))
                .thenReturn(Optional.of(sampleEvent("evt-found")));

        EventResponse response = eventService.getEventById("evt-found");

        assertThat(response.getEventId()).isEqualTo("evt-found");
        assertThat(response.getAccountId()).isEqualTo("acc-001");
        assertThat(response.getType()).isEqualTo("CREDIT");
        assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.getCurrency()).isEqualTo("USD");
    }

    // ─────────────────────────────────────────────────────────
    // Test data helpers
    // ─────────────────────────────────────────────────────────

    /** Builds a TransactionEvent with deterministic field values for mock returns. */
    private TransactionEvent sampleEvent(String eventId) {
        TransactionEvent e = new TransactionEvent();
        e.setEventId(eventId);
        e.setAccountId("acc-001");
        e.setType("CREDIT");
        e.setAmount(new BigDecimal("100.00"));
        e.setCurrency("USD");
        e.setEventTimestamp(Instant.parse("2026-05-01T10:00:00Z"));
        e.setReceivedAt(Instant.parse("2026-05-01T10:00:01Z"));
        return e;
    }

    /** Builds an EventRequest, bypassing Bean Validation (validation is tested in EventControllerTest). */
    private EventRequest buildRequest(String eventId, String type,
                                       String amount, String timestamp) {
        EventRequest req = new EventRequest();
        req.setEventId(eventId);
        req.setAccountId("acc-001");
        req.setType(type);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency("USD");
        req.setEventTimestamp(timestamp);
        return req;
    }
}
