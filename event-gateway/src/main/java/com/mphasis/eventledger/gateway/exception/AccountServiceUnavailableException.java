package com.mphasis.eventledger.gateway.exception;

/**
 * Thrown when the Account Service is unavailable (down, circuit breaker open, or timeout).
 * The Gateway returns a 503 Service Unavailable response to the client.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
