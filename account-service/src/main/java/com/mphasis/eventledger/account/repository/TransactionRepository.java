package com.mphasis.eventledger.account.repository;

import com.mphasis.eventledger.account.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Repository for Transaction entities.
 * Provides query methods for account-based lookups and balance computation.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns paginated transactions for an account, ordered chronologically (most recent first).
     */
    Page<Transaction> findByAccountIdOrderByEventTimestampDesc(String accountId, Pageable pageable);

    /**
     * Computes the net balance for an account as a single aggregation query.
     * balance = sum(CREDIT amounts) - sum(DEBIT amounts)
     * Returns 0 if no transactions exist for the account.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.mphasis.eventledger.account.model.TransactionType.CREDIT " +
            "THEN t.amount ELSE -t.amount END), 0) FROM Transaction t WHERE t.accountId = :accountId")
    BigDecimal computeBalance(@Param("accountId") String accountId);

    /**
     * Counts the number of transactions for an account.
     */
    long countByAccountId(String accountId);
}
