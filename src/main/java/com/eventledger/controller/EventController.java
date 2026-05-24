package com.eventledger.controller;

import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.service.EventService;
import com.eventledger.service.EventService.SubmitResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping(params = "account")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getEventsByAccount(accountId));
    }
}
