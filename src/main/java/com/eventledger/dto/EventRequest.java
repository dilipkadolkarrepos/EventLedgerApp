package com.eventledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Request body for submitting a transaction event.
 *
 * <p><b>Why {@code @JsonProperty} is present alongside the global {@code SNAKE_CASE} strategy</b>:
 * The application sets {@code spring.jackson.property-naming-strategy=SNAKE_CASE}, which converts
 * getter-derived names to snake_case for <em>both</em> serialisation and deserialisation.
 * Without the explicit {@code @JsonProperty("camelCase")} annotations below, Jackson would
 * expect the inbound JSON to use {@code "event_id"}, {@code "account_id"}, etc.
 * The annotations lock the inbound field names to camelCase (e.g. {@code "eventId"}) so that
 * request and response contracts are symmetric for clients.
 * Jackson honours annotations on private fields by merging them with the matching public getter
 * when building the property descriptor.
 */
@Schema(description = "Payload for submitting a new transaction event")
public class EventRequest {

    /**
     * Caller-supplied idempotency key.  Must be unique across all events.
     */
    @Schema(description = "Caller-assigned idempotency key; must be unique across all events",
            example = "evt-abc-001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "eventId must not be blank")
    @JsonProperty("eventId")
    private String eventId;

    @Schema(description = "Account that owns this transaction",
            example = "acc-xyz-999", requiredMode = Schema.RequiredMode.REQUIRED)
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
    @Schema(description = "Direction of the transaction", allowableValues = {"CREDIT", "DEBIT"},
            example = "CREDIT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "type must not be blank")
    @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
    @JsonProperty("type")
    private String type;

    @Schema(description = "Transaction amount; must be greater than zero",
            example = "250.00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than zero")
    @JsonProperty("amount")
    private BigDecimal amount;

    @Schema(description = "ISO 4217 currency code", example = "USD",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "currency must not be blank")
    @JsonProperty("currency")
    private String currency;

    /** ISO-8601 timestamp string, e.g. {@code 2024-01-15T10:30:00Z}. */
    @Schema(description = "Business timestamp of the event; ISO 8601 instant (e.g. 2026-05-24T10:30:00Z)",
            example = "2026-05-24T10:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "eventTimestamp must not be blank")
    @JsonProperty("eventTimestamp")
    private String eventTimestamp;

    /** Optional free-form JSON object; may be {@code null}. */
    @Schema(description = "Optional free-form metadata object",
            example = "{\"source\": \"payment-gateway\", \"ref\": \"TXN-99\"}",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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
