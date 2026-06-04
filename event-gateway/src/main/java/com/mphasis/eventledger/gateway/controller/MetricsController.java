package com.mphasis.eventledger.gateway.controller;

import com.mphasis.eventledger.gateway.observability.RequestMetricsFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes custom request metrics collected by the RequestMetricsFilter.
 */
@RestController
public class MetricsController {

    private final RequestMetricsFilter requestMetricsFilter;

    public MetricsController(RequestMetricsFilter requestMetricsFilter) {
        this.requestMetricsFilter = requestMetricsFilter;
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        return ResponseEntity.ok(requestMetricsFilter.getMetrics());
    }
}
