package com.mphasis.eventledger.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom metric filter that tracks request counts by endpoint and status code.
 * <p>
 * Satisfies the "at least one custom metric" observability requirement.
 * Metrics are exposed via the {@code GET /metrics} endpoint.
 */
@Component
@Order(2)
public class RequestMetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsFilter.class);

    // Thread-safe counters: key = "METHOD /path STATUS_CODE"
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final AtomicLong totalRequests = new AtomicLong(0);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            String key = request.getMethod() + " " + request.getRequestURI() + " " + response.getStatus();
            requestCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            totalRequests.incrementAndGet();

            log.debug("Request completed: {} {} -> {} ({}ms)", request.getMethod(),
                    request.getRequestURI(), response.getStatus(), duration);
        }
    }

    /**
     * Returns a snapshot of all collected metrics.
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("totalRequests", totalRequests.get());

        Map<String, Long> breakdown = new java.util.LinkedHashMap<>();
        requestCounts.forEach((key, count) -> breakdown.put(key, count.get()));
        metrics.put("requestCounts", breakdown);

        return metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Don't track the metrics endpoint itself to avoid recursion
        return "/metrics".equals(request.getRequestURI());
    }
}
