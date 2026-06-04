package com.mphasis.eventledger.account.service;

import com.mphasis.eventledger.account.model.Transaction;
import com.mphasis.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Business logic for account transaction management, balance computation, and account queries.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final TransactionRepository transactionRepository;

    public AccountService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Result of a transaction application attempt.
     * @param transaction the persisted transaction
     * @param duplicate   true if the transaction already existed (idempotent replay)
     */
    public record ApplyResult(Transaction transaction, boolean duplicate) {
    }

    /**
     * Applies a transaction to an account. Idempotent — if a transaction with the same
     * eventId already exists, returns the existing transaction without modification.
     */
    public ApplyResult applyTransaction(Transaction transaction) {
        return transactionRepository.findById(transaction.getEventId())
                .map(existing -> {
                    log.info("Duplicate transaction detected: eventId={}", transaction.getEventId());
                    return new ApplyResult(existing, true);
                })
                .orElseGet(() -> {
                    try {
                        Transaction saved = transactionRepository.save(transaction);
                        log.info("Transaction applied: eventId={}, accountId={}, type={}, amount={}",
                                saved.getEventId(), saved.getAccountId(),
                                saved.getType(), saved.getAmount());
                        return new ApplyResult(saved, false);
                    } catch (DataIntegrityViolationException e) {
                        // Race condition: another request inserted between our check and save
                        Transaction existing = transactionRepository.findById(transaction.getEventId())
                                .orElseThrow(() -> new RuntimeException(
                                        "Unexpected: constraint violation but transaction not found for eventId: "
                                                + transaction.getEventId()));
                        log.info("Duplicate transaction detected (race condition): eventId={}",
                                transaction.getEventId());
                        return new ApplyResult(existing, true);
                    }
                });
    }

    /**
     * Computes the net balance for an account.
     * balance = sum(CREDIT amounts) - sum(DEBIT amounts)
     * Returns BigDecimal.ZERO for accounts with no transactions.
     */
    public BigDecimal getBalance(String accountId) {
        return transactionRepository.computeBalance(accountId);
    }

    /**
     * Returns account details including balance and recent transactions.
     */
    public AccountDetails getAccountDetails(String accountId) {
        BigDecimal balance = transactionRepository.computeBalance(accountId);
        long transactionCount = transactionRepository.countByAccountId(accountId);
        Page<Transaction> recentTransactions = transactionRepository
                .findByAccountIdOrderByEventTimestampDesc(accountId, PageRequest.of(0, 10));

        return new AccountDetails(accountId, balance, transactionCount,
                recentTransactions.getContent());
    }

    /**
     * Value object holding account details for the GET /accounts/{accountId} endpoint.
     */
    public record AccountDetails(
            String accountId,
            BigDecimal balance,
            long totalTransactions,
            java.util.List<Transaction> recentTransactions
    ) {
    }
}
