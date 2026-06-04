package com.mphasis.eventledger.gateway.dto;

import java.util.List;

/**
 * Standardized error response body.
 */
public class ErrorResponse {

    private String error;
    private List<String> details;

    public ErrorResponse() {
    }

    public ErrorResponse(String error) {
        this.error = error;
        this.details = List.of();
    }

    public ErrorResponse(String error, List<String> details) {
        this.error = error;
        this.details = details;
    }

    // --- Getters and Setters ---

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }
}
