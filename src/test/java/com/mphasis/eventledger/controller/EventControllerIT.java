package com.mphasis.eventledger.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Event Ledger API.
 * Each test method gets a fresh database context to avoid test interdependencies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EventControllerIT {

    @Autowired
    private MockMvc mockMvc;

    // ========================
    // Helper methods
    // ========================

    private String buildEventJson(String eventId, String accountId, String type,
                                   double amount, String currency, String timestamp) {
        return buildEventJson(eventId, accountId, type, amount, currency, timestamp, null);
    }

    private String buildEventJson(String eventId, String accountId, String type,
                                   double amount, String currency, String timestamp,
                                   String metadataJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"eventId\":\"").append(eventId).append("\",");
        sb.append("\"accountId\":\"").append(accountId).append("\",");
        sb.append("\"type\":\"").append(type).append("\",");
        sb.append("\"amount\":").append(amount).append(",");
        sb.append("\"currency\":\"").append(currency).append("\",");
        sb.append("\"eventTimestamp\":\"").append(timestamp).append("\"");
        if (metadataJson != null) {
            sb.append(",\"metadata\":").append(metadataJson);
        }
        sb.append("}");
        return sb.toString();
    }

    private void postEvent(String json, int expectedStatus) throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(expectedStatus));
    }

    // ========================
    // Happy Path Tests
    // ========================

    @Test
    void shouldCreateEvent_returns201() throws Exception {
        String json = buildEventJson("evt-001", "acct-123", "CREDIT",
                150.00, "USD", "2026-05-15T14:02:11Z");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty());
    }

    @Test
    void shouldCreateEventWithMetadata() throws Exception {
        String json = buildEventJson("evt-meta", "acct-123", "CREDIT",
                100.00, "USD", "2026-05-15T14:00:00Z",
                "{\"source\":\"mainframe-batch\",\"batchId\":\"B-9042\"}");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metadata").isNotEmpty());
    }

    @Test
    void shouldRetrieveEventById() throws Exception {
        String json = buildEventJson("evt-get", "acct-123", "DEBIT",
                50.00, "USD", "2026-05-15T15:00:00Z");
        postEvent(json, 201);

        mockMvc.perform(get("/events/evt-get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-get"))
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.amount").value(50.00));
    }

    // ========================
    // Idempotency Tests
    // ========================

    @Test
    void shouldReturnExistingEvent_onDuplicate_returns200() throws Exception {
        String json = buildEventJson("evt-dup", "acct-123", "CREDIT",
                100.00, "USD", "2026-05-15T14:00:00Z");

        // First submission → 201
        postEvent(json, 201);

        // Second submission → 200 (idempotent)
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"));
    }

    @Test
    void shouldNotAlterBalance_onDuplicateSubmission() throws Exception {
        String json = buildEventJson("evt-dup-bal", "acct-bal-dup", "CREDIT",
                200.00, "USD", "2026-05-15T14:00:00Z");

        // Submit same event 3 times
        postEvent(json, 201);
        postEvent(json, 200);
        postEvent(json, 200);

        // Balance should still be 200, not 600
        mockMvc.perform(get("/accounts/acct-bal-dup/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(200.00));
    }

    // ========================
    // Out-of-Order Tolerance Tests
    // ========================

    @Test
    void shouldListEventsInChronologicalOrder_regardlessOfArrivalOrder() throws Exception {
        // Submit events out of order (event at 14:00 arrives AFTER event at 16:00)
        postEvent(buildEventJson("evt-3", "acct-ooo", "CREDIT", 300.00, "USD", "2026-05-15T16:00:00Z"), 201);
        postEvent(buildEventJson("evt-1", "acct-ooo", "CREDIT", 100.00, "USD", "2026-05-15T14:00:00Z"), 201);
        postEvent(buildEventJson("evt-2", "acct-ooo", "DEBIT",  200.00, "USD", "2026-05-15T15:00:00Z"), 201);

        // Listing should return in chronological order: evt-1, evt-2, evt-3
        mockMvc.perform(get("/events").param("account", "acct-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.content[1].eventId").value("evt-2"))
                .andExpect(jsonPath("$.content[2].eventId").value("evt-3"));
    }

    @Test
    void shouldComputeCorrectBalance_regardlessOfArrivalOrder() throws Exception {
        // Events arrive out of order but balance should always be correct
        postEvent(buildEventJson("evt-b3", "acct-bal-ooo", "CREDIT", 500.00, "USD", "2026-05-15T16:00:00Z"), 201);
        postEvent(buildEventJson("evt-b1", "acct-bal-ooo", "CREDIT", 100.00, "USD", "2026-05-15T14:00:00Z"), 201);
        postEvent(buildEventJson("evt-b2", "acct-bal-ooo", "DEBIT",  150.00, "USD", "2026-05-15T15:00:00Z"), 201);

        // balance = 100 + 500 - 150 = 450
        mockMvc.perform(get("/accounts/acct-bal-ooo/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(450.00));
    }

    // ========================
    // Balance Computation Tests
    // ========================

    @Test
    void shouldComputeCorrectBalance_afterMixedCreditsAndDebits() throws Exception {
        postEvent(buildEventJson("evt-c1", "acct-mix", "CREDIT", 1000.00, "USD", "2026-05-15T10:00:00Z"), 201);
        postEvent(buildEventJson("evt-c2", "acct-mix", "DEBIT",   250.00, "USD", "2026-05-15T11:00:00Z"), 201);
        postEvent(buildEventJson("evt-c3", "acct-mix", "CREDIT",  500.00, "USD", "2026-05-15T12:00:00Z"), 201);
        postEvent(buildEventJson("evt-c4", "acct-mix", "DEBIT",   100.00, "USD", "2026-05-15T13:00:00Z"), 201);

        // balance = 1000 - 250 + 500 - 100 = 1150
        mockMvc.perform(get("/accounts/acct-mix/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-mix"))
                .andExpect(jsonPath("$.balance").value(1150.00));
    }

    @Test
    void shouldReturnZeroBalance_forUnknownAccount() throws Exception {
        mockMvc.perform(get("/accounts/acct-nonexistent/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
    }

    // ========================
    // Validation Tests
    // ========================

    @Test
    void shouldReject_missingRequiredFields() throws Exception {
        // Empty body — all required fields missing
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    void shouldReject_negativeAmount() throws Exception {
        String json = buildEventJson("evt-neg", "acct-123", "CREDIT",
                -50.00, "USD", "2026-05-15T14:00:00Z");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("amount")));
    }

    @Test
    void shouldReject_zeroAmount() throws Exception {
        String json = buildEventJson("evt-zero", "acct-123", "CREDIT",
                0.00, "USD", "2026-05-15T14:00:00Z");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("amount")));
    }

    @Test
    void shouldReject_unknownEventType() throws Exception {
        String json = buildEventJson("evt-unknown", "acct-123", "REFUND",
                50.00, "USD", "2026-05-15T14:00:00Z");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    // ========================
    // Error Handling Tests
    // ========================

    @Test
    void shouldReturn404_forNonexistentEvent() throws Exception {
        mockMvc.perform(get("/events/evt-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Event not found with id: evt-does-not-exist"));
    }

    // ========================
    // Pagination Tests (Bonus)
    // ========================

    @Test
    void shouldPaginateEventListing() throws Exception {
        // Create 5 events
        for (int i = 1; i <= 5; i++) {
            postEvent(buildEventJson("evt-pg-" + i, "acct-page", "CREDIT",
                    100.00 * i, "USD", "2026-05-15T" + String.format("%02d", 10 + i) + ":00:00Z"), 201);
        }

        // Request page 0, size 2
        mockMvc.perform(get("/events")
                        .param("account", "acct-page")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].eventId").value("evt-pg-1"))
                .andExpect(jsonPath("$.content[1].eventId").value("evt-pg-2"))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        // Request page 2, size 2 (last page with 1 element)
        mockMvc.perform(get("/events")
                        .param("account", "acct-page")
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].eventId").value("evt-pg-5"));
    }
}
