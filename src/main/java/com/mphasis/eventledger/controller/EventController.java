package com.mphasis.eventledger.controller;

import com.mphasis.eventledger.dto.BalanceResponse;
import com.mphasis.eventledger.dto.EventRequest;
import com.mphasis.eventledger.dto.EventResponse;
import com.mphasis.eventledger.model.Event;
import com.mphasis.eventledger.model.EventType;
import com.mphasis.eventledger.service.EventService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller exposing the Event Ledger API endpoints.
 */
@RestController
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * POST /events — Submit a transaction event.
     * Returns 201 Created for new events, 200 OK for duplicates (idempotency).
     */
    @PostMapping("/events")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        // Validate event type manually for a clearer error message
        EventType eventType;
        try {
            eventType = EventType.valueOf(request.getType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown event type: '" + request.getType() + "'. Must be CREDIT or DEBIT.");
        }

        // Store metadata as a simple toString representation
        String metadataStr = null;
        Map<String, Object> metadata = request.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            metadataStr = metadata.toString();
        }

        Event event = new Event(
                request.getEventId(),
                request.getAccountId(),
                eventType,
                request.getAmount(),
                request.getCurrency(),
                request.getEventTimestamp(),
                metadataStr
        );

        EventService.IngestResult result = eventService.ingestEvent(event);

        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(EventResponse.fromEntity(result.event()));
    }

    /**
     * GET /events/{id} — Retrieve a single event by its ID.
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        Event event = eventService.getEvent(id);
        return ResponseEntity.ok(EventResponse.fromEntity(event));
    }

    /**
     * GET /events?account={accountId} — List events for an account, ordered by eventTimestamp.
     * Supports pagination via 'page' and 'size' query parameters.
     */
    @GetMapping("/events")
    public ResponseEntity<Page<EventResponse>> getEventsForAccount(
            @RequestParam String account,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<EventResponse> events = eventService.getEventsForAccount(account, pageable)
                .map(EventResponse::fromEntity);
        return ResponseEntity.ok(events);
    }

    /**
     * GET /accounts/{accountId}/balance — Get the current computed balance for an account.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        var balance = eventService.getBalance(accountId);
        return ResponseEntity.ok(new BalanceResponse(accountId, balance, "USD"));
    }
}
