package com.mphasis.eventledger.gateway.repository;

import com.mphasis.eventledger.gateway.model.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for Event entities in the Gateway's local database.
 * Event storage in the Gateway enables graceful degradation — GET endpoints
 * can serve data even when the Account Service is unavailable.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, String> {

    /**
     * Returns paginated events for an account, ordered chronologically by eventTimestamp.
     * Correct ordering regardless of event arrival order.
     */
    Page<Event> findByAccountIdOrderByEventTimestampAsc(String accountId, Pageable pageable);
}
