package com.eventledger.controller;

import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.dto.PagedResponse;
import com.eventledger.service.EventService;
import com.eventledger.service.EventService.SubmitResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated          // enables @Min / @Max on method parameters via AOP proxy
@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        SubmitResult result = eventService.submitEvent(request);
        if (result.isNew()) {
            return ResponseEntity.status(201).body(result.getResponse());
        }
        return ResponseEntity.ok(result.getResponse());
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEventById(@PathVariable String eventId) {
        return ResponseEntity.ok(eventService.getEventById(eventId));
    }

    /**
     * Returns a paginated, chronologically ordered list of events for an account.
     *
     * <p><b>Validation gate</b>: {@code page} and {@code size} are validated by
     * Spring AOP before the service is reached. Violations throw
     * {@code ConstraintViolationException}, which the global handler maps to a
     * 400 response with a {@code details} array — identical in shape to body
     * validation errors, so clients need only one error-handling path.
     *
     * @param accountId the account whose events are queried
     * @param page      zero-based page index (default 0, must be &ge; 0)
     * @param size      number of events per page (default 20, must be 1–100)
     */
    @GetMapping(params = "account")
    public ResponseEntity<PagedResponse<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = 100, message = "size must be <= 100") int size) {

        Page<EventResponse> result = eventService.getEventsByAccount(
                accountId,
                PageRequest.of(page, size, Sort.by("eventTimestamp").ascending()));

        return ResponseEntity.ok(PagedResponse.from(result));
    }
}
