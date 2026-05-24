package com.eventledger.dto;

import com.eventledger.model.TransactionEvent;

import java.math.BigDecimal;

public class EventResponse {

    private String eventId;
    private String accountId;
    private String type;
    private BigDecimal amount;
    private String currency;
    private String eventTimestamp;
    private String metadata;
    private String receivedAt;

    private EventResponse() {}

    public static EventResponse from(TransactionEvent e) {
        EventResponse response = new EventResponse();
        response.eventId = e.getEventId();
        response.accountId = e.getAccountId();
        response.type = e.getType();
        response.amount = e.getAmount();
        response.currency = e.getCurrency();
        response.eventTimestamp = e.getEventTimestamp() != null
                ? e.getEventTimestamp().toString() : null;
        response.metadata = e.getMetadata();
        response.receivedAt = e.getReceivedAt() != null
                ? e.getReceivedAt().toString() : null;
        return response;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getEventTimestamp() {
        return eventTimestamp;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getReceivedAt() {
        return receivedAt;
    }
}
