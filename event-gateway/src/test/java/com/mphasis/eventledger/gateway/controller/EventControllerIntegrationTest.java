package com.mphasis.eventledger.gateway.controller;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Event Gateway API.
 * Uses WireMock to simulate the Account Service.
 */
@SpringBootTest(properties = {
        "account-service.base-url=${wiremock.server.baseUrl}"
})
@AutoConfigureMockMvc
@EnableWireMock
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EventControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @InjectWireMock
    private WireMockServer wireMock;

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

    private void stubAccountServiceTransaction(String accountId) {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/accounts/" + accountId + "/transactions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"eventId\":\"stub\",\"accountId\":\"" + accountId + "\"}")));
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
        stubAccountServiceTransaction("acct-123");

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

        // Verify the Account Service was called
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/accounts/acct-123/transactions")));
    }

    @Test
    void shouldCreateEventWithMetadata() throws Exception {
        stubAccountServiceTransaction("acct-123");

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
        stubAccountServiceTransaction("acct-123");

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
        stubAccountServiceTransaction("acct-123");

        String json = buildEventJson("evt-dup", "acct-123", "CREDIT",
                100.00, "USD", "2026-05-15T14:00:00Z");

        postEvent(json, 201);

        // Second submission → 200 (idempotent), should NOT call Account Service again
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"));

        // Account Service should only have been called once (for the first submission)
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/accounts/acct-123/transactions")));
    }

    // ========================
    // Out-of-Order Tolerance Tests
    // ========================

    @Test
    void shouldListEventsInChronologicalOrder_regardlessOfArrivalOrder() throws Exception {
        stubAccountServiceTransaction("acct-ooo");

        // Submit events out of order
        postEvent(buildEventJson("evt-3", "acct-ooo", "CREDIT", 300.00, "USD", "2026-05-15T16:00:00Z"), 201);
        postEvent(buildEventJson("evt-1", "acct-ooo", "CREDIT", 100.00, "USD", "2026-05-15T14:00:00Z"), 201);
        postEvent(buildEventJson("evt-2", "acct-ooo", "DEBIT",  200.00, "USD", "2026-05-15T15:00:00Z"), 201);

        // Listing should return in chronological order
        mockMvc.perform(get("/events").param("account", "acct-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.content[1].eventId").value("evt-2"))
                .andExpect(jsonPath("$.content[2].eventId").value("evt-3"));
    }

    // ========================
    // Validation Tests
    // ========================

    @Test
    void shouldReject_missingRequiredFields() throws Exception {
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
    // Pagination Tests
    // ========================

    @Test
    void shouldPaginateEventListing() throws Exception {
        stubAccountServiceTransaction("acct-page");

        for (int i = 1; i <= 5; i++) {
            postEvent(buildEventJson("evt-pg-" + i, "acct-page", "CREDIT",
                    100.00 * i, "USD", "2026-05-15T" + String.format("%02d", 10 + i) + ":00:00Z"), 201);
        }

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
    }

    // ========================
    // Balance Proxy (Account Service)
    // ========================

    @Test
    void shouldProxyBalanceFromAccountService() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/accounts/acct-123/balance"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acct-123\",\"balance\":750.00,\"currency\":\"USD\"}")));

        mockMvc.perform(get("/accounts/acct-123/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.balance").value(750.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    // ========================
    // Trace ID Propagation
    // ========================

    @Test
    void shouldPropagateTraceIdToAccountService() throws Exception {
        stubAccountServiceTransaction("acct-trace");

        String json = buildEventJson("evt-trace", "acct-trace", "CREDIT",
                100.00, "USD", "2026-05-15T14:00:00Z");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("X-Trace-Id", "my-custom-trace-id"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "my-custom-trace-id"));

        // Verify the trace ID was forwarded to Account Service
        wireMock.verify(postRequestedFor(urlPathEqualTo("/accounts/acct-trace/transactions"))
                .withHeader("X-Trace-Id", equalTo("my-custom-trace-id")));
    }

    @Test
    void shouldGenerateTraceId_whenNotProvided() throws Exception {
        stubAccountServiceTransaction("acct-gen");

        String json = buildEventJson("evt-gen", "acct-gen", "CREDIT",
                100.00, "USD", "2026-05-15T14:00:00Z");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"));

        // Verify a trace ID was forwarded (any non-empty value)
        wireMock.verify(postRequestedFor(urlPathEqualTo("/accounts/acct-gen/transactions"))
                .withHeader("X-Trace-Id", matching(".+")));
    }

    // ========================
    // Health Check
    // ========================

    @Test
    void shouldReturnHealth() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/health"))
                .willReturn(aResponse().withStatus(200)));

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("event-gateway"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.accountService").value("UP"));
    }
}
