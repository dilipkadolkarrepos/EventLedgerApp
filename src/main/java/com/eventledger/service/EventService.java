package com.eventledger.service;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.exception.InvalidEventException;
import com.eventledger.model.TransactionEvent;
import com.eventledger.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Submits an event, guaranteeing exactly-once persistence even under concurrent
     * requests for the same {@code eventId}.
     *
     * <h3>Serial duplicate (idempotency fast-path)</h3>
     * {@link EventRepository#findByEventId} is checked first. If a record already
     * exists it is returned immediately — {@code save()} is never called.
     *
     * <h3>Concurrent duplicate (race-condition path)</h3>
     * Two threads can both pass the {@code findByEventId} check before either has
     * committed. The second thread to call {@code save()} hits the {@code UNIQUE}
     * constraint on {@code event_id} and receives a {@link DataIntegrityViolationException}.
     * That exception is caught here; the winner's record is re-fetched from the database
     * and returned with {@code isNew = false} — the same contract as a serial duplicate.
     *
     * <h3>Why there is no {@code @Transactional} on this method</h3>
     * If this method ran inside one transaction, Hibernate would mark the session as
     * <em>rollback-only</em> the moment {@code save()} triggered the constraint error,
     * making a subsequent {@code findByEventId} call impossible within the same session.
     * Without an outer transaction each repository call gets its own short transaction
     * (courtesy of {@code SimpleJpaRepository}), so the failed save rolls back cleanly
     * and the re-query opens a fresh read transaction.
     */
    public SubmitResult submitEvent(EventRequest request) {
        // ── 1. Serial duplicate fast-path ────────────────────────────────────
        Optional<TransactionEvent> existing = eventRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            log.debug("Duplicate submission for eventId={}, returning existing record", request.getEventId());
            return new SubmitResult(EventResponse.from(existing.get()), false);
        }

        // ── 2. Build entity ──────────────────────────────────────────────────
        Instant eventTimestamp = parseEventTimestamp(request.getEventTimestamp());
        String metadataJson = serializeMetadata(request);

        TransactionEvent event = new TransactionEvent();
        event.setEventId(request.getEventId());
        event.setAccountId(request.getAccountId());
        event.setType(request.getType());
        event.setAmount(request.getAmount().setScale(4, RoundingMode.HALF_UP));
        event.setCurrency(request.getCurrency());
        event.setEventTimestamp(eventTimestamp);
        event.setMetadata(metadataJson);

        // ── 3. Persist — handle concurrent duplicate ─────────────────────────
        try {
            TransactionEvent saved = eventRepository.save(event);
            log.info("Persisted new event eventId={} accountId={}", saved.getEventId(), saved.getAccountId());
            return new SubmitResult(EventResponse.from(saved), true);
        } catch (DataIntegrityViolationException ex) {
            // Another thread committed the same eventId between our check and our save.
            // Re-read the winner's record and treat this like a normal duplicate (200).
            log.debug("Concurrent duplicate detected for eventId={}, re-querying winner record",
                    request.getEventId());
            return eventRepository.findByEventId(request.getEventId())
                    .map(e -> new SubmitResult(EventResponse.from(e), false))
                    .orElseThrow(() -> new IllegalStateException(
                            "UNIQUE constraint fired on event_id but record not found: "
                            + request.getEventId(), ex));
        }
    }

    /**
     * Fetches a single event by its business key for audit or idempotency inspection.
     */
    @Transactional(readOnly = true)
    public EventResponse getEventById(String eventId) {
        TransactionEvent event = eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found with eventId: " + eventId));
        return EventResponse.from(event);
    }

    /**
     * Returns the full, unsliced ordered ledger for an account.
     * Ordering is by the business-supplied {@code eventTimestamp}, not by insertion
     * time, so out-of-order arrivals produce a chronologically correct history.
     * Retained for internal/test use; the API layer calls the paginated overload.
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(EventResponse::from)
                .toList();
    }

    /**
     * Returns a paginated, chronologically ordered ledger for an account.
     * Sort direction is enforced by the {@link Pageable} passed from the controller
     * ({@code eventTimestamp ASC}), so out-of-order arrivals are transparently
     * reordered at query time regardless of physical insertion order.
     */
    @Transactional(readOnly = true)
    public Page<EventResponse> getEventsByAccount(String accountId, Pageable pageable) {
        return eventRepository.findByAccountId(accountId, pageable)
                .map(EventResponse::from);
    }

    /**
     * Computes the net balance for an account by summing CREDITs and subtracting
     * DEBITs in a single database round-trip. Currency is derived from the earliest
     * event; the caller is responsible for asserting single-currency consistency
     * before trusting the balance in a multi-currency scenario.
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        List<String> currencies = eventRepository.findCurrencyByAccountId(accountId);
        if (currencies.isEmpty()) {
            throw new EventNotFoundException("No events found for accountId: " + accountId);
        }
        BigDecimal balance = eventRepository.computeBalanceByAccountId(accountId)
                .setScale(4, RoundingMode.HALF_UP);
        return new BalanceResponse(accountId, balance, currencies.get(0));
    }

    // --- helpers ---

    private Instant parseEventTimestamp(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new InvalidEventException(
                    "eventTimestamp must be a valid ISO 8601 instant (e.g. 2024-01-15T10:30:00Z), got: " + raw);
        }
    }

    private String serializeMetadata(EventRequest request) {
        if (request.getMetadata() == null || request.getMetadata().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.getMetadata());
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("metadata could not be serialized to JSON: " + e.getOriginalMessage());
        }
    }

    // --- inner result type ---

    public static class SubmitResult {

        private final EventResponse response;
        private final boolean isNew;

        public SubmitResult(EventResponse response, boolean isNew) {
            this.response = response;
            this.isNew = isNew;
        }

        public EventResponse getResponse() {
            return response;
        }

        public boolean isNew() {
            return isNew;
        }
    }
}
