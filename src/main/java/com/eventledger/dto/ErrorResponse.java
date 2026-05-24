package com.eventledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Schema(description = "Uniform error response shape returned for all 4xx / 5xx responses")
public class ErrorResponse {

    @Schema(description = "ISO 8601 timestamp of when the error was generated",
            example = "2026-05-24T10:31:00.000Z")
    private final String timestamp;

    @Schema(description = "HTTP status code", example = "400")
    private final int status;

    @Schema(description = "Short error category", example = "Validation failed")
    private final String error;

    @Schema(description = "Human-readable explanation of the error",
            example = "One or more fields failed validation")
    private final String message;

    @Schema(description = "List of individual constraint violations; empty for non-validation errors",
            example = "[\"amount must be greater than zero\", \"eventId must not be blank\"]")
    private final List<String> details;

    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, Collections.emptyList());
    }

    public ErrorResponse(int status, String error, String message, List<String> details) {
        this.timestamp = Instant.now().toString();
        this.status = status;
        this.error = error;
        this.message = message;
        this.details = details != null ? List.copyOf(details) : Collections.emptyList();
    }

    public String getTimestamp()    { return timestamp; }
    public int getStatus()          { return status; }
    public String getError()        { return error; }
    public String getMessage()      { return message; }
    public List<String> getDetails(){ return details; }
}
