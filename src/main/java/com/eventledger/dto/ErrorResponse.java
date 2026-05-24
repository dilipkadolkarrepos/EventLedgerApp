package com.eventledger.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public class ErrorResponse {

    private final String timestamp;
    private final int status;
    private final String error;
    private final String message;
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

    public String getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getDetails() {
        return details;
    }
}
