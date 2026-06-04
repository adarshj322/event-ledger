package com.mphasis.eventledger.gateway.dto;

import com.mphasis.eventledger.gateway.model.Event;
import com.mphasis.eventledger.gateway.model.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for an event. Mirrors the entity fields exposed to the client.
 */
public class EventResponse {

    private String eventId;
    private String accountId;
    private EventType type;
    private BigDecimal amount;
    private String currency;
    private Instant eventTimestamp;
    private Instant receivedAt;
    private String metadata;

    /**
     * Factory method to convert a domain entity to a response DTO.
     */
    public static EventResponse fromEntity(Event event) {
        EventResponse response = new EventResponse();
        response.setEventId(event.getEventId());
        response.setAccountId(event.getAccountId());
        response.setType(event.getType());
        response.setAmount(event.getAmount());
        response.setCurrency(event.getCurrency());
        response.setEventTimestamp(event.getEventTimestamp());
        response.setReceivedAt(event.getReceivedAt());
        response.setMetadata(event.getMetadata());
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

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
