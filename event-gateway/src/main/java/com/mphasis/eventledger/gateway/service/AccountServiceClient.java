package com.mphasis.eventledger.gateway.service;

import com.mphasis.eventledger.gateway.dto.BalanceResponse;
import com.mphasis.eventledger.gateway.exception.AccountServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * REST client for communicating with the Account Service.
 * Wraps all outgoing calls with a Resilience4j circuit breaker to handle
 * Account Service failures gracefully.
 *
 * <p><b>Circuit Breaker Configuration:</b></p>
 * <ul>
 *   <li>Sliding window: 5 calls</li>
 *   <li>Failure rate threshold: 50%</li>
 *   <li>Wait in open state: 10 seconds</li>
 *   <li>Permitted calls in half-open: 3</li>
 * </ul>
 *
 * <p><b>Why circuit breaker over retry?</b></p>
 * The POST /accounts/{id}/transactions endpoint applies a financial transaction.
 * Blind retries could cause duplicate balance mutations if the Account Service
 * processed the request but the response was lost. The circuit breaker instead
 * fails fast when the downstream is unhealthy, protecting both services.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public AccountServiceClient(
            @Value("${account-service.base-url:http://localhost:8081}") String baseUrl,
            RestClient.Builder restClientBuilder) {

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slowCallRateThreshold(80)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.circuitBreaker = registry.circuitBreaker("accountService");

        // Log circuit breaker state transitions
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker state transition: {}",
                        event.getStateTransition()));
    }

    /**
     * Applies a transaction to the Account Service.
     * Protected by circuit breaker — throws AccountServiceUnavailableException
     * if the Account Service is down or the circuit is open.
     */
    public void applyTransaction(String accountId, String eventId, String type,
                                  BigDecimal amount, String currency, Instant eventTimestamp) {
        try {
            circuitBreaker.executeRunnable(() -> {
                Map<String, Object> body = Map.of(
                        "eventId", eventId,
                        "type", type,
                        "amount", amount,
                        "currency", currency,
                        "eventTimestamp", eventTimestamp.toString()
                );

                restClient.post()
                        .uri("/accounts/{accountId}/transactions", accountId)
                        .header(TRACE_ID_HEADER, MDC.get("traceId"))
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                            throw new RuntimeException("Account Service returned " + resp.getStatusCode());
                        })
                        .toBodilessEntity();

                log.info("Transaction forwarded to Account Service: eventId={}, accountId={}",
                        eventId, accountId);
            });
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.error("Circuit breaker OPEN — Account Service call blocked for eventId={}", eventId);
            throw new AccountServiceUnavailableException(
                    "Account Service is temporarily unavailable (circuit breaker open)");
        } catch (ResourceAccessException e) {
            log.error("Account Service unreachable: eventId={}, error={}", eventId, e.getMessage());
            throw new AccountServiceUnavailableException(
                    "Account Service is unreachable: " + e.getMessage());
        } catch (AccountServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to forward transaction to Account Service: eventId={}", eventId, e);
            throw new AccountServiceUnavailableException(
                    "Failed to communicate with Account Service: " + e.getMessage());
        }
    }

    /**
     * Retrieves the balance for an account from the Account Service.
     * Protected by circuit breaker — throws AccountServiceUnavailableException on failure.
     */
    public BalanceResponse getBalance(String accountId) {
        try {
            return circuitBreaker.executeSupplier(() -> {
                BalanceResponse response = restClient.get()
                        .uri("/accounts/{accountId}/balance", accountId)
                        .header(TRACE_ID_HEADER, MDC.get("traceId"))
                        .retrieve()
                        .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                            throw new RuntimeException("Account Service returned " + resp.getStatusCode());
                        })
                        .body(BalanceResponse.class);

                log.info("Balance retrieved from Account Service: accountId={}", accountId);
                return response;
            });
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.error("Circuit breaker OPEN — balance query blocked for accountId={}", accountId);
            throw new AccountServiceUnavailableException(
                    "Account Service is temporarily unavailable (circuit breaker open)");
        } catch (AccountServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve balance from Account Service: accountId={}", accountId, e);
            throw new AccountServiceUnavailableException(
                    "Failed to communicate with Account Service: " + e.getMessage());
        }
    }

    /**
     * Checks if the Account Service is reachable (used by health endpoint).
     */
    public boolean isHealthy() {
        try {
            restClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the current circuit breaker state for diagnostics.
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState().name();
    }
}
