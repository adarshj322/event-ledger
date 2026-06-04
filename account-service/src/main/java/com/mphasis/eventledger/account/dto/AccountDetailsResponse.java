package com.mphasis.eventledger.account.dto;

import com.mphasis.eventledger.account.model.Transaction;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for the GET /accounts/{accountId} endpoint.
 * Returns account details including balance and recent transactions.
 */
public class AccountDetailsResponse {

    private String accountId;
    private BigDecimal balance;
    private String currency;
    private long totalTransactions;
    private List<TransactionResponse> recentTransactions;

    public AccountDetailsResponse() {
    }

    public AccountDetailsResponse(String accountId, BigDecimal balance, String currency,
                                   long totalTransactions, List<Transaction> transactions) {
        this.accountId = accountId;
        this.balance = balance;
        this.currency = currency;
        this.totalTransactions = totalTransactions;
        this.recentTransactions = transactions.stream()
                .map(TransactionResponse::fromEntity)
                .toList();
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

    public long getTotalTransactions() {
        return totalTransactions;
    }

    public void setTotalTransactions(long totalTransactions) {
        this.totalTransactions = totalTransactions;
    }

    public List<TransactionResponse> getRecentTransactions() {
        return recentTransactions;
    }

    public void setRecentTransactions(List<TransactionResponse> recentTransactions) {
        this.recentTransactions = recentTransactions;
    }
}
