package com.mphasis.eventledger.gateway.service;

import com.mphasis.eventledger.gateway.exception.EventNotFoundException;
import com.mphasis.eventledger.gateway.model.Event;
import com.mphasis.eventledger.gateway.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Business logic for event ingestion, retrieval, and forwarding to the Account Service.
 * <p>
 * The Gateway stores events in its own local database for durability and graceful degradation.
 * On successful local storage, it forwards the transaction to the Account Service for
 * balance computation.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
    }

    /**
     * Result of an event ingestion attempt.
     * @param event     the persisted event
     * @param duplicate true if the event already existed (idempotent replay)
     */
    public record IngestResult(Event event, boolean duplicate) {
    }

    /**
     * Ingests a new event. If an event with the same eventId already exists,
     * returns the existing event and flags it as a duplicate.
     * <p>
     * For new events, the transaction is forwarded to the Account Service.
     * If the Account Service is unavailable, the exception propagates to the controller
     * which returns a 503.
     */
    public IngestResult ingestEvent(Event event) {
        return eventRepository.findById(event.getEventId())
                .map(existing -> {
                    log.info("Duplicate event detected: eventId={}", event.getEventId());
                    return new IngestResult(existing, true);
                })
                .orElseGet(() -> {
                    try {
                        Event saved = eventRepository.save(event);
                        log.info("Event stored locally: eventId={}, accountId={}",
                                saved.getEventId(), saved.getAccountId());

                        // Forward to Account Service for balance computation
                        accountServiceClient.applyTransaction(
                                saved.getAccountId(),
                                saved.getEventId(),
                                saved.getType().name(),
                                saved.getAmount(),
                                saved.getCurrency(),
                                saved.getEventTimestamp()
                        );

                        return new IngestResult(saved, false);
                    } catch (DataIntegrityViolationException e) {
                        // Race condition: another thread inserted between our check and save
                        Event existing = eventRepository.findById(event.getEventId())
                                .orElseThrow(() -> new RuntimeException(
                                        "Unexpected: constraint violation but event not found for id: "
                                                + event.getEventId()));
                        log.info("Duplicate event detected (race condition): eventId={}",
                                event.getEventId());
                        return new IngestResult(existing, true);
                    }
                });
    }

    /**
     * Retrieves a single event by its ID from the Gateway's local database.
     * Works even when the Account Service is unavailable (graceful degradation).
     *
     * @throws EventNotFoundException if no event with the given ID exists
     */
    public Event getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /**
     * Returns a paginated list of events for an account from the Gateway's local database.
     * Works even when the Account Service is unavailable (graceful degradation).
     */
    public Page<Event> getEventsForAccount(String accountId, Pageable pageable) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId, pageable);
    }
}
