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
     * Submits an event, guaranteeing exactly-once persistence via the business key.
     * If an event with the same eventId already exists the existing record is returned
     * immediately — no second write occurs. Callers can distinguish a fresh save from
     * a replay via {@link SubmitResult#isNew()}.
     */
    @Transactional
    public SubmitResult submitEvent(EventRequest request) {
        Optional<TransactionEvent> existing = eventRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            log.debug("Duplicate submission for eventId={}, returning existing record", request.getEventId());
            return new SubmitResult(EventResponse.from(existing.get()), false);
        }

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

        TransactionEvent saved = eventRepository.save(event);
        log.info("Persisted new event eventId={} accountId={}", saved.getEventId(), saved.getAccountId());
        return new SubmitResult(EventResponse.from(saved), true);
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
     * Returns the full ordered ledger for an account.
     * Ordering is by the business-supplied {@code eventTimestamp}, not by insertion
     * time, so out-of-order arrivals (delayed producers, retries) produce a
     * chronologically correct history regardless of when each event was received.
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(EventResponse::from)
                .toList();
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
