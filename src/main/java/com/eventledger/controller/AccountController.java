package com.eventledger.controller;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.ErrorResponse;
import com.eventledger.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Accounts", description = "Account-level aggregations")
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final EventService eventService;

    public AccountController(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(
            summary = "Get the net balance for an account",
            description = """
                    Computes `balance = Σ CREDIT amounts − Σ DEBIT amounts` in a single \
                    SQL aggregate query. The `currency` field is taken from the account's \
                    earliest event. The balance can be negative when debits exceed credits."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance computed successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BalanceResponse.class))),
            @ApiResponse(responseCode = "404", description = "No events exist for that accountId",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @Parameter(description = "Account identifier", example = "acc-xyz-999")
            @PathVariable String accountId) {
        return ResponseEntity.ok(eventService.getBalance(accountId));
    }
}
