package com.mphasis.eventledger.repository;

import com.mphasis.eventledger.model.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Repository for Event entities.
 * Provides query methods for account-based lookups and balance computation.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, String> {

    /**
     * Returns paginated events for an account, ordered chronologically by eventTimestamp.
     * This ensures correct ordering regardless of the order events were received.
     */
    Page<Event> findByAccountIdOrderByEventTimestampAsc(String accountId, Pageable pageable);

    /**
     * Computes the net balance for an account as a single aggregation query.
     * balance = sum(CREDIT amounts) - sum(DEBIT amounts)
     * Returns 0 if no events exist for the account.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.type = com.mphasis.eventledger.model.EventType.CREDIT " +
            "THEN e.amount ELSE -e.amount END), 0) FROM Event e WHERE e.accountId = :accountId")
    BigDecimal computeBalance(@Param("accountId") String accountId);
}
