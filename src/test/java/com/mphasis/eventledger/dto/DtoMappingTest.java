package com.mphasis.eventledger.dto;

import com.mphasis.eventledger.model.Event;
import com.mphasis.eventledger.model.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DTO mapping and construction logic.
 */
class DtoMappingTest {

    // ─── EventResponse.fromEntity Tests ────────────────────────────

    @Test
    @DisplayName("EventResponse.fromEntity — maps all fields correctly from entity")
    void fromEntity_mapsAllFields() {
        Event event = new Event(
                "evt-001",
                "acct-123",
                EventType.CREDIT,
                new BigDecimal("250.75"),
                "USD",
                Instant.parse("2026-05-15T10:00:00Z"),
                "{source=batch}"
        );
        event.setReceivedAt(Instant.parse("2026-05-15T10:05:00Z"));

        EventResponse response = EventResponse.fromEntity(event);

        assertEquals("evt-001", response.getEventId());
        assertEquals("acct-123", response.getAccountId());
        assertEquals(EventType.CREDIT, response.getType());
        assertEquals(new BigDecimal("250.75"), response.getAmount());
        assertEquals("USD", response.getCurrency());
        assertEquals(Instant.parse("2026-05-15T10:00:00Z"), response.getEventTimestamp());
        assertEquals(Instant.parse("2026-05-15T10:05:00Z"), response.getReceivedAt());
        assertEquals("{source=batch}", response.getMetadata());
    }

    @Test
    @DisplayName("EventResponse.fromEntity — handles null metadata gracefully")
    void fromEntity_nullMetadata_mapsCorrectly() {
        Event event = new Event(
                "evt-002",
                "acct-456",
                EventType.DEBIT,
                new BigDecimal("100.00"),
                "EUR",
                Instant.parse("2026-05-16T12:00:00Z"),
                null
        );

        EventResponse response = EventResponse.fromEntity(event);

        assertNull(response.getMetadata());
        assertEquals("evt-002", response.getEventId());
        assertEquals(EventType.DEBIT, response.getType());
    }

    // ─── BalanceResponse Tests ─────────────────────────────────────

    @Test
    @DisplayName("BalanceResponse — constructor sets all fields correctly")
    void balanceResponse_constructorSetsFields() {
        BalanceResponse response = new BalanceResponse("acct-123", new BigDecimal("750.00"), "USD");

        assertEquals("acct-123", response.getAccountId());
        assertEquals(new BigDecimal("750.00"), response.getBalance());
        assertEquals("USD", response.getCurrency());
    }

    @Test
    @DisplayName("BalanceResponse — zero balance for empty account")
    void balanceResponse_zeroBalance() {
        BalanceResponse response = new BalanceResponse("acct-empty", BigDecimal.ZERO, "USD");

        assertEquals(BigDecimal.ZERO, response.getBalance());
    }

    // ─── ErrorResponse Tests ───────────────────────────────────────

    @Test
    @DisplayName("ErrorResponse — single-message constructor sets error and empty details")
    void errorResponse_singleMessage() {
        ErrorResponse response = new ErrorResponse("Something went wrong");

        assertEquals("Something went wrong", response.getError());
        assertNotNull(response.getDetails());
        assertTrue(response.getDetails().isEmpty());
    }

    @Test
    @DisplayName("ErrorResponse — message with details constructor sets both fields")
    void errorResponse_withDetails() {
        var details = java.util.List.of("field1: is required", "field2: must be positive");
        ErrorResponse response = new ErrorResponse("Validation failed", details);

        assertEquals("Validation failed", response.getError());
        assertEquals(2, response.getDetails().size());
        assertEquals("field1: is required", response.getDetails().get(0));
    }
}
