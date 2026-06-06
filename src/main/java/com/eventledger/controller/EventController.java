package com.eventledger.controller;

import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.kafka.EventProducer;
import com.eventledger.service.EventService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST controller for the /events resource.
 *
 * <h3>Architecture change (event-driven)</h3>
 * <ul>
 *   <li>{@code POST /events} no longer calls {@link EventService} directly.
 *       Instead it publishes the validated request to Kafka and returns
 *       {@code HTTP 202 Accepted}.  Persistence happens asynchronously inside
 *       {@code EventConsumer}.</li>
 *   <li>{@code GET} endpoints are unchanged — they read directly from the
 *       database via {@link EventService} for low-latency, synchronous responses.</li>
 * </ul>
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    /** Timeout (seconds) to wait for the Kafka broker to acknowledge a send. */
    private static final int KAFKA_SEND_TIMEOUT_SECONDS = 5;

    private final EventService eventService;
    private final EventProducer eventProducer;

    public EventController(EventService eventService, EventProducer eventProducer) {
        this.eventService = eventService;
        this.eventProducer = eventProducer;
    }

    // ── Write path (async via Kafka) ─────────────────────────────────────────

    /**
     * Accept an event and publish it to the Kafka inbound topic.
     *
     * <p>Returns {@code 202 Accepted} when the broker acknowledges the message.
     * The actual persistence happens asynchronously in {@code EventConsumer}.
     * Returns {@code 503 Service Unavailable} when the broker is unreachable.</p>
     */
    @PostMapping
    public ResponseEntity<?> submitEvent(@Valid @RequestBody EventRequest request) {
        String eventId = request.getEventId();
        try {
            // Block up to KAFKA_SEND_TIMEOUT_SECONDS for broker acknowledgment.
            // Using .get() here keeps the HTTP response tied to broker durability
            // (acks=all), so the caller can trust the message is safely queued.
            eventProducer.sendEvent(request)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("Event accepted and queued: eventId={}", eventId);
            return ResponseEntity.accepted()
                    .body(new AcceptedResponse(eventId, "Event received and queued for processing"));

        } catch (TimeoutException e) {
            log.error("Kafka broker did not acknowledge event within {}s: eventId={}",
                    KAFKA_SEND_TIMEOUT_SECONDS, eventId, e);
            return ResponseEntity.status(503)
                    .body(new AcceptedResponse(eventId, "Kafka unavailable — please retry"));

        } catch (ExecutionException e) {
            log.error("Failed to publish event to Kafka: eventId={}", eventId, e.getCause());
            return ResponseEntity.status(503)
                    .body(new AcceptedResponse(eventId, "Event could not be queued: " + e.getCause().getMessage()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while publishing event: eventId={}", eventId, e);
            return ResponseEntity.status(503)
                    .body(new AcceptedResponse(eventId, "Request interrupted — please retry"));
        }
    }

    // ── Read paths (synchronous, direct DB) ──────────────────────────────────

    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEventById(@PathVariable String eventId) {
        return ResponseEntity.ok(eventService.getEventById(eventId));
    }

    @GetMapping(params = "account")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getEventsByAccount(accountId));
    }

    // ── Inner response DTO ────────────────────────────────────────────────────

    /**
     * Lightweight acceptance response body for POST /events.
     * Intentionally minimal — the caller polls GET /events/{eventId} for full details.
     */
    public record AcceptedResponse(String eventId, String message) {}
}
