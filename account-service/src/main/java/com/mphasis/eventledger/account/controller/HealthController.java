package com.mphasis.eventledger.account.controller;

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
 * Health check endpoint for the Account Service.
 * Returns service status and basic diagnostics including database connectivity.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("service", "account-service");
        health.put("status", "UP");
        health.put("database", checkDatabase());
        health.put("timestamp", Instant.now().toString());

        String overallStatus = "UP".equals(health.get("database")) ? "UP" : "DEGRADED";
        health.put("status", overallStatus);

        log.info("Health check: status={}", overallStatus);
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
