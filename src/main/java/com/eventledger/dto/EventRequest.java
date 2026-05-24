package com.eventledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.Map;

public class EventRequest {

    /**
     * Caller-supplied idempotency key.  Must be unique across all events.
     */
    @NotBlank(message = "eventId must not be blank")
    @JsonProperty("eventId")
    private String eventId;

    @NotBlank(message = "accountId must not be blank")
    @JsonProperty("accountId")
    private String accountId;

    /**
     * Direction of the transaction.
     * <p>
     * {@code @NotBlank} is included alongside {@code @Pattern} because
     * JSR-380 {@code @Pattern} skips {@code null} values and would silently
     * accept a missing field otherwise.
     */
    @NotBlank(message = "type must not be blank")
    @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
    @JsonProperty("type")
    private String type;

    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than zero")
    @JsonProperty("amount")
    private BigDecimal amount;

    @NotBlank(message = "currency must not be blank")
    @JsonProperty("currency")
    private String currency;

    /** ISO-8601 timestamp string, e.g. {@code 2024-01-15T10:30:00Z}. */
    @NotBlank(message = "eventTimestamp must not be blank")
    @JsonProperty("eventTimestamp")
    private String eventTimestamp;

    /** Optional free-form JSON object; may be {@code null}. */
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(String eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
