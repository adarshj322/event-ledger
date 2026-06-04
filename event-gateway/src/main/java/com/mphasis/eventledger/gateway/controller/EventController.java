package com.mphasis.eventledger.gateway.controller;

import com.mphasis.eventledger.gateway.dto.BalanceResponse;
import com.mphasis.eventledger.gateway.dto.EventRequest;
import com.mphasis.eventledger.gateway.dto.EventResponse;
import com.mphasis.eventledger.gateway.model.Event;
import com.mphasis.eventledger.gateway.model.EventType;
import com.mphasis.eventledger.gateway.service.AccountServiceClient;
import com.mphasis.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * REST controller for the Event Gateway API.
 * Public-facing entry point for all client requests.
 */
@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;

    public EventController(EventService eventService, AccountServiceClient accountServiceClient) {
        this.eventService = eventService;
        this.accountServiceClient = accountServiceClient;
    }

    /**
     * POST /events — Submit a transaction event.
     * Returns 201 Created for new events, 200 OK for duplicates (idempotency).
     * Returns 503 if Account Service is unavailable.
     */
    @PostMapping("/events")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        EventType eventType;
        try {
            eventType = EventType.valueOf(request.getType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown event type: '" + request.getType() + "'. Must be CREDIT or DEBIT.");
        }

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

        log.info("Event {}: eventId={}, accountId={}, status={}",
                result.duplicate() ? "acknowledged (duplicate)" : "created",
                request.getEventId(), request.getAccountId(), status);

        return ResponseEntity.status(status).body(EventResponse.fromEntity(result.event()));
    }

    /**
     * GET /events/{id} — Retrieve a single event by its ID.
     * Works even when Account Service is unavailable (local data only).
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        Event event = eventService.getEvent(id);
        return ResponseEntity.ok(EventResponse.fromEntity(event));
    }

    /**
     * GET /events?account={accountId} — List events for an account, ordered by eventTimestamp.
     * Works even when Account Service is unavailable (local data only).
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
     * GET /accounts/{accountId}/balance — Proxied to Account Service.
     * Returns 503 if Account Service is unavailable.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        BalanceResponse balance = accountServiceClient.getBalance(accountId);
        return ResponseEntity.ok(balance);
    }
}
