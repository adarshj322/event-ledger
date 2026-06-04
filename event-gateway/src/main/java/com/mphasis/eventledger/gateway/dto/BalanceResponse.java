package com.mphasis.eventledger.gateway.dto;

import java.math.BigDecimal;

/**
 * Response DTO for the account balance — proxied from Account Service.
 */
public class BalanceResponse {

    private String accountId;
    private BigDecimal balance;
    private String currency;

    public BalanceResponse() {
    }

    public BalanceResponse(String accountId, BigDecimal balance, String currency) {
        this.accountId = accountId;
        this.balance = balance;
        this.currency = currency;
    }

    // --- Getters and Setters ---

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
