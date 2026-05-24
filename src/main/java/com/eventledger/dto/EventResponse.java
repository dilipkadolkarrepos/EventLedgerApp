package com.eventledger.dto;

import com.eventledger.model.TransactionEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Representation of a persisted transaction event")
public class EventResponse {

    @Schema(description = "Caller-assigned idempotency key", example = "evt-abc-001")
    private String eventId;

    @Schema(description = "Account that owns this transaction", example = "acc-xyz-999")
    private String accountId;

    @Schema(description = "Direction of the transaction", allowableValues = {"CREDIT", "DEBIT"},
            example = "CREDIT")
    private String type;

    @Schema(description = "Transaction amount rounded to 4 decimal places", example = "250.0000")
    private BigDecimal amount;

    @Schema(description = "ISO 4217 currency code", example = "USD")
    private String currency;

    @Schema(description = "Business timestamp of the event (ISO 8601)", example = "2026-05-24T10:30:00Z")
    private String eventTimestamp;

    @Schema(description = "Serialised metadata JSON string; null when no metadata was supplied",
            example = "{\"source\":\"payment-gateway\",\"ref\":\"TXN-99\"}")
    private String metadata;

    @Schema(description = "Server-assigned ingestion timestamp (ISO 8601)", example = "2026-05-24T10:30:01.123Z")
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

    public String getEventId()        { return eventId; }
    public String getAccountId()      { return accountId; }
    public String getType()           { return type; }
    public BigDecimal getAmount()     { return amount; }
    public String getCurrency()       { return currency; }
    public String getEventTimestamp() { return eventTimestamp; }
    public String getMetadata()       { return metadata; }
    public String getReceivedAt()     { return receivedAt; }
}
