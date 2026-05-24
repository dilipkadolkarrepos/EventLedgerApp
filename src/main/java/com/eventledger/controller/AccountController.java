package com.eventledger.controller;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.service.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final EventService eventService;

    public AccountController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        return ResponseEntity.ok(eventService.getBalance(accountId));
    }
}
