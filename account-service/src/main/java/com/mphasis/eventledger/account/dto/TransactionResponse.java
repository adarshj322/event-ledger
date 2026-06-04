package com.mphasis.eventledger.account.dto;

import com.mphasis.eventledger.account.model.Transaction;
import com.mphasis.eventledger.account.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for a transaction.
 */
public class TransactionResponse {

    private String eventId;
    private String accountId;
    private TransactionType type;
    private BigDecimal amount;
    private String currency;
    private Instant eventTimestamp;
    private Instant receivedAt;

    /**
     * Factory method to convert a domain entity to a response DTO.
     */
    public static TransactionResponse fromEntity(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.setEventId(transaction.getEventId());
        response.setAccountId(transaction.getAccountId());
        response.setType(transaction.getType());
        response.setAmount(transaction.getAmount());
        response.setCurrency(transaction.getCurrency());
        response.setEventTimestamp(transaction.getEventTimestamp());
        response.setReceivedAt(transaction.getReceivedAt());
        return response;
    }

    // --- Getters and Setters ---

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

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
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

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
