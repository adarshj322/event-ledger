package com.mphasis.eventledger.service;

import com.mphasis.eventledger.exception.EventNotFoundException;
import com.mphasis.eventledger.model.Event;
import com.mphasis.eventledger.repository.EventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Business logic for event ingestion, retrieval, and balance computation.
 */
@Service
public class EventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
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
     * Idempotency is enforced by checking for an existing event before persisting.
     * The unique primary key constraint on eventId serves as a safety net for
     * concurrent requests — only one thread will succeed in inserting.
     */
    public IngestResult ingestEvent(Event event) {
        // Check for duplicate before saving
        return eventRepository.findById(event.getEventId())
                .map(existing -> new IngestResult(existing, true))
                .orElseGet(() -> {
                    try {
                        Event saved = eventRepository.save(event);
                        return new IngestResult(saved, false);
                    } catch (DataIntegrityViolationException e) {
                        // Race condition: another thread inserted between our check and save
                        Event existing = eventRepository.findById(event.getEventId())
                                .orElseThrow(() -> new RuntimeException(
                                        "Unexpected: constraint violation but event not found for id: " + event.getEventId()));
                        return new IngestResult(existing, true);
                    }
                });
    }

    /**
     * Retrieves a single event by its ID.
     *
     * @throws EventNotFoundException if no event with the given ID exists
     */
    public Event getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /**
     * Returns a paginated list of events for an account, ordered by eventTimestamp ascending.
     * This ensures chronological ordering regardless of arrival order.
     */
    public Page<Event> getEventsForAccount(String accountId, Pageable pageable) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId, pageable);
    }

    /**
     * Computes the net balance for an account.
     * balance = sum(CREDIT amounts) - sum(DEBIT amounts)
     * Returns BigDecimal.ZERO for accounts with no events.
     */
    public BigDecimal getBalance(String accountId) {
        return eventRepository.computeBalance(accountId);
    }
}
