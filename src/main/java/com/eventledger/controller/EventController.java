package com.eventledger.controller;

import com.eventledger.dto.ErrorResponse;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.dto.PagedResponse;
import com.eventledger.service.EventService;
import com.eventledger.service.EventService.SubmitResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Events", description = "Record and query financial transaction events")
@Validated          // enables @Min / @Max on method parameters via AOP proxy
@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    // ── POST /events ────────────────────────────────────────────────────────

    @Operation(
            summary = "Submit a transaction event",
            description = """
                    Records a new transaction event. Submitting the same `eventId` more than \
                    once is safe — the original record is returned without a second write \
                    (idempotent)."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Event persisted for the first time",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "200", description = "Duplicate eventId — original event returned, no write performed",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or eventTimestamp is not a valid ISO 8601 instant",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        SubmitResult result = eventService.submitEvent(request);
        if (result.isNew()) {
            return ResponseEntity.status(201).body(result.getResponse());
        }
        return ResponseEntity.ok(result.getResponse());
    }

    // ── GET /events/{eventId} ───────────────────────────────────────────────

    @Operation(
            summary = "Get a single event by its business key",
            description = "Retrieves a single transaction event by its caller-supplied `eventId`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "404", description = "No event with that eventId exists",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEventById(
            @Parameter(description = "Caller-assigned event identifier", example = "evt-abc-001")
            @PathVariable String eventId) {
        return ResponseEntity.ok(eventService.getEventById(eventId));
    }

    // ── GET /events?account= ────────────────────────────────────────────────

    @Operation(
            summary = "List events for an account (paginated)",
            description = """
                    Returns a paginated, chronologically ordered list of events for an account. \
                    Events that arrive out of order are transparently reordered by `eventTimestamp`."""
    )
    @ApiResponses({
            // No explicit schema here — springdoc infers PagedResponse<EventResponse>
            // from the method's return type, avoiding the raw-generic-type ambiguity.
            @ApiResponse(responseCode = "200",
                    description = "Page of events (empty content array when account has no events)"),
            @ApiResponse(responseCode = "400", description = "Invalid page or size parameter",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(params = "account")
    public ResponseEntity<PagedResponse<EventResponse>> getEventsByAccount(
            @Parameter(description = "Account identifier to filter by", example = "acc-xyz-999", required = true)
            @RequestParam("account") String accountId,

            @Parameter(description = "Zero-based page index (must be >= 0)", example = "0")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0") int page,

            @Parameter(description = "Number of events per page (1–100)", example = "20")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = 100, message = "size must be <= 100") int size) {

        Page<EventResponse> result = eventService.getEventsByAccount(
                accountId,
                PageRequest.of(page, size, Sort.by("eventTimestamp").ascending()));

        return ResponseEntity.ok(PagedResponse.from(result));
    }
}
