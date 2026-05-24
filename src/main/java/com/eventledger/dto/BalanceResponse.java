package com.eventledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Net balance computed for an account")
public class BalanceResponse {

    @Schema(description = "Account identifier", example = "acc-xyz-999")
    private final String accountId;

    @Schema(description = "Net balance (CREDIT sum minus DEBIT sum); may be negative", example = "1500.0000")
    private final BigDecimal balance;

    @Schema(description = "Currency taken from the account's earliest event", example = "USD")
    private final String currency;

    public BalanceResponse(String accountId, BigDecimal balance, String currency) {
        this.accountId = accountId;
        this.balance = balance;
        this.currency = currency;
    }

    public String getAccountId()   { return accountId; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency()    { return currency; }
}
