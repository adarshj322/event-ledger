package com.mphasis.eventledger.gateway.controller;

import com.mphasis.eventledger.gateway.service.AccountServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoint for the Event Gateway.
 * Reports the status of the Gateway itself, its database, and the Account Service.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final AccountServiceClient accountServiceClient;

    public HealthController(DataSource dataSource, AccountServiceClient accountServiceClient) {
        this.dataSource = dataSource;
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("service", "event-gateway");

        String dbStatus = checkDatabase();
        boolean accountServiceHealthy = accountServiceClient.isHealthy();

        health.put("database", dbStatus);
        health.put("accountService", accountServiceHealthy ? "UP" : "DOWN");
        health.put("circuitBreaker", accountServiceClient.getCircuitBreakerState());
        health.put("timestamp", Instant.now().toString());

        String overallStatus;
        if (!"UP".equals(dbStatus)) {
            overallStatus = "DOWN";
        } else if (!accountServiceHealthy) {
            overallStatus = "DEGRADED";
        } else {
            overallStatus = "UP";
        }
        health.put("status", overallStatus);

        log.info("Health check: status={}, db={}, accountService={}, circuitBreaker={}",
                overallStatus, dbStatus, accountServiceHealthy ? "UP" : "DOWN",
                accountServiceClient.getCircuitBreakerState());

        return ResponseEntity.ok(health);
    }

    private String checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.error("Database health check failed", e);
            return "DOWN";
        }
    }
}
