package com.mphasis.eventledger.gateway.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for resiliency and graceful degradation behavior.
 * Verifies the Gateway handles Account Service failures correctly.
 */
@SpringBootTest(properties = {
        "account-service.base-url=${wiremock.server.baseUrl}"
})
@AutoConfigureMockMvc
@EnableWireMock
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ResiliencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @InjectWireMock
    private WireMockServer wireMock;

    private String buildEventJson(String eventId, String accountId) {
        return String.format(
                "{\"eventId\":\"%s\",\"accountId\":\"%s\",\"type\":\"CREDIT\",\"amount\":100.00," +
                        "\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
                eventId, accountId);
    }

    // ========================
    // Graceful Degradation — POST
    // ========================

    @Test
    @DisplayName("POST /events returns 503 when Account Service is down")
    void postEvent_accountServiceDown_returns503() throws Exception {
        // Account Service returns 500
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildEventJson("evt-fail", "acct-fail")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", containsString("Account Service")));
    }

    @Test
    @DisplayName("POST /events returns 503 when Account Service is unreachable (connection refused)")
    void postEvent_accountServiceUnreachable_returns503() throws Exception {
        // Stop WireMock to simulate unreachable service
        wireMock.stop();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildEventJson("evt-unreach", "acct-unreach")))
                .andExpect(status().isServiceUnavailable());

        // Restart for cleanup
        wireMock.start();
    }

    // ========================
    // Graceful Degradation — GET (should still work)
    // ========================

    @Test
    @DisplayName("GET /events/{id} works when Account Service is down (local data)")
    void getEventById_accountServiceDown_stillWorks() throws Exception {
        // First, create an event with Account Service up
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildEventJson("evt-local", "acct-local")))
                .andExpect(status().isCreated());

        // Now stop Account Service
        wireMock.stop();

        // GET should still work from local database
        mockMvc.perform(get("/events/evt-local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-local"));

        // Restart for cleanup
        wireMock.start();
    }

    @Test
    @DisplayName("GET /events?account=... works when Account Service is down (local data)")
    void getEventsForAccount_accountServiceDown_stillWorks() throws Exception {
        // First, create events with Account Service up
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildEventJson("evt-list-1", "acct-list")))
                .andExpect(status().isCreated());

        // Now stop Account Service
        wireMock.stop();

        // Listing should still work from local database
        mockMvc.perform(get("/events").param("account", "acct-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value("evt-list-1"));

        // Restart for cleanup
        wireMock.start();
    }

    // ========================
    // Graceful Degradation — Balance
    // ========================

    @Test
    @DisplayName("GET /accounts/{id}/balance returns 503 when Account Service is down")
    void getBalance_accountServiceDown_returns503() throws Exception {
        wireMock.stop();

        mockMvc.perform(get("/accounts/acct-123/balance"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", containsString("Account Service")));

        wireMock.start();
    }

    // ========================
    // Health Check Degradation
    // ========================

    @Test
    @DisplayName("Health endpoint shows DEGRADED when Account Service is down")
    void health_accountServiceDown_showsDegraded() throws Exception {
        // Don't stub the health endpoint — it will fail
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/health"))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.accountService").value("DOWN"));
    }

    @Test
    @DisplayName("Health endpoint shows UP when Account Service is healthy")
    void health_accountServiceUp_showsUp() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/health"))
                .willReturn(aResponse().withStatus(200)));

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.accountService").value("UP"));
    }
}
