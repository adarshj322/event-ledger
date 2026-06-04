package com.mphasis.eventledger.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that manages distributed trace IDs for the Event Gateway.
 * <p>
 * For each incoming request:
 * <ol>
 *   <li>Reads the {@code X-Trace-Id} header from the client (if present).</li>
 *   <li>If missing, generates a new UUID as the trace ID.</li>
 *   <li>Stores the trace ID in the SLF4J MDC so all log entries include it.</li>
 *   <li>Adds the trace ID to the response headers for client-side correlation.</li>
 * </ol>
 * <p>
 * The trace ID is propagated to the Account Service via the
 * {@link com.mphasis.eventledger.gateway.service.AccountServiceClient},
 * which reads it from the MDC and sets it as a request header.
 */
@Component
@Order(1)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}
