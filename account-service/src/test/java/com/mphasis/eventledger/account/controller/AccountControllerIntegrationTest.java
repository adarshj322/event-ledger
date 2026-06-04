package com.mphasis.eventledger.account.controller;

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
 * Integration tests for the Account Service API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String buildTransactionJson(String eventId, String type, double amount,
                                         String currency, String timestamp) {
        return String.format(
                "{\"eventId\":\"%s\",\"type\":\"%s\",\"amount\":%s,\"currency\":\"%s\",\"eventTimestamp\":\"%s\"}",
                eventId, type, amount, currency, timestamp);
    }

    private void postTransaction(String accountId, String json, int expectedStatus) throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(expectedStatus));
    }

    // ========================
    // Transaction Application
    // ========================

    @Test
    void shouldApplyTransaction_returns201() throws Exception {
        String json = buildTransactionJson("evt-001", "CREDIT", 150.00, "USD", "2026-05-15T14:02:11Z");

        mockMvc.perform(post("/accounts/acct-123/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(150.00));
    }

    @Test
    void shouldReturnExistingTransaction_onDuplicate_returns200() throws Exception {
        String json = buildTransactionJson("evt-dup", "CREDIT", 100.00, "USD", "2026-05-15T14:00:00Z");

        postTransaction("acct-123", json, 201);

        mockMvc.perform(post("/accounts/acct-123/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"));
    }

    // ========================
    // Balance Computation
    // ========================

    @Test
    void shouldComputeCorrectBalance() throws Exception {
        postTransaction("acct-bal", buildTransactionJson("evt-b1", "CREDIT", 1000.00, "USD", "2026-05-15T10:00:00Z"), 201);
        postTransaction("acct-bal", buildTransactionJson("evt-b2", "DEBIT", 250.00, "USD", "2026-05-15T11:00:00Z"), 201);
        postTransaction("acct-bal", buildTransactionJson("evt-b3", "CREDIT", 500.00, "USD", "2026-05-15T12:00:00Z"), 201);

        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-bal"))
                .andExpect(jsonPath("$.balance").value(1250.00));
    }

    @Test
    void shouldReturnZeroBalance_forUnknownAccount() throws Exception {
        mockMvc.perform(get("/accounts/acct-unknown/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void shouldNotAlterBalance_onDuplicateTransaction() throws Exception {
        String json = buildTransactionJson("evt-dup-bal", "CREDIT", 200.00, "USD", "2026-05-15T14:00:00Z");

        postTransaction("acct-dup-bal", json, 201);
        postTransaction("acct-dup-bal", json, 200);
        postTransaction("acct-dup-bal", json, 200);

        mockMvc.perform(get("/accounts/acct-dup-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(200.00));
    }

    // ========================
    // Account Details
    // ========================

    @Test
    void shouldReturnAccountDetails() throws Exception {
        postTransaction("acct-det", buildTransactionJson("evt-d1", "CREDIT", 100.00, "USD", "2026-05-15T10:00:00Z"), 201);
        postTransaction("acct-det", buildTransactionJson("evt-d2", "DEBIT", 30.00, "USD", "2026-05-15T11:00:00Z"), 201);

        mockMvc.perform(get("/accounts/acct-det"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-det"))
                .andExpect(jsonPath("$.balance").value(70.00))
                .andExpect(jsonPath("$.totalTransactions").value(2))
                .andExpect(jsonPath("$.recentTransactions", hasSize(2)));
    }

    // ========================
    // Validation
    // ========================

    @Test
    void shouldReject_invalidTransactionType() throws Exception {
        String json = buildTransactionJson("evt-bad", "REFUND", 50.00, "USD", "2026-05-15T14:00:00Z");

        mockMvc.perform(post("/accounts/acct-123/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReject_missingFields() throws Exception {
        mockMvc.perform(post("/accounts/acct-123/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    // ========================
    // Health Check
    // ========================

    @Test
    void shouldReturnHealth() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("account-service"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    // ========================
    // Trace ID Propagation
    // ========================

    @Test
    void shouldEchoTraceIdFromHeader() throws Exception {
        mockMvc.perform(get("/health")
                        .header("X-Trace-Id", "test-trace-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "test-trace-123"));
    }

    @Test
    void shouldGenerateTraceId_whenNotProvided() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
