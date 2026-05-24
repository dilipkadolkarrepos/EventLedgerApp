package com.eventledger.dto;

import java.math.BigDecimal;

public class BalanceResponse {

    private final String accountId;
    private final BigDecimal balance;
    private final String currency;

    public BalanceResponse(String accountId, BigDecimal balance, String currency) {
        this.accountId = accountId;
        this.balance = balance;
        this.currency = currency;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }
}
