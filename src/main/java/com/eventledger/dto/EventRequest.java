package com.eventledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.Map;

public class EventRequest {

    @NotBlank
    @JsonProperty("event_id")
    private String eventId;

    @NotBlank
    @JsonProperty("account_id")
    private String accountId;

    @Pattern(regexp = "CREDIT|DEBIT")
    @JsonProperty("type")
    private String type;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @JsonProperty("amount")
    private BigDecimal amount;

    @NotBlank
    @JsonProperty("currency")
    private String currency;

    @NotBlank
    @JsonProperty("event_timestamp")
    private String eventTimestamp;

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
