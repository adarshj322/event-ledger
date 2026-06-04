package com.mphasis.eventledger.account.service;

import com.mphasis.eventledger.account.model.Transaction;
import com.mphasis.eventledger.account.model.TransactionType;
import com.mphasis.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountService}.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AccountService accountService;

    private Transaction sampleTransaction;

    @BeforeEach
    void setUp() {
        sampleTransaction = new Transaction(
                "evt-001",
                "acct-123",
                TransactionType.CREDIT,
                new BigDecimal("500.00"),
                "USD",
                Instant.parse("2026-05-15T10:00:00Z")
        );
    }

    // ─── Apply Transaction Tests ──────────────────────────────────

    @Test
    @DisplayName("applyTransaction — saves new transaction and returns duplicate=false")
    void applyTransaction_newTransaction_savedSuccessfully() {
        when(transactionRepository.findById("evt-001")).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(sampleTransaction);

        AccountService.ApplyResult result = accountService.applyTransaction(sampleTransaction);

        assertFalse(result.duplicate());
        assertEquals("evt-001", result.transaction().getEventId());
        verify(transactionRepository).findById("evt-001");
        verify(transactionRepository).save(sampleTransaction);
    }

    @Test
    @DisplayName("applyTransaction — returns existing transaction with duplicate=true")
    void applyTransaction_duplicateTransaction_returnsExisting() {
        when(transactionRepository.findById("evt-001")).thenReturn(Optional.of(sampleTransaction));

        AccountService.ApplyResult result = accountService.applyTransaction(sampleTransaction);

        assertTrue(result.duplicate());
        assertEquals("evt-001", result.transaction().getEventId());
        verify(transactionRepository).findById("evt-001");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyTransaction — handles race condition via DataIntegrityViolationException")
    void applyTransaction_raceCondition_catchesConstraintViolation() {
        when(transactionRepository.findById("evt-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(sampleTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        AccountService.ApplyResult result = accountService.applyTransaction(sampleTransaction);

        assertTrue(result.duplicate());
        assertEquals("evt-001", result.transaction().getEventId());
        verify(transactionRepository, times(2)).findById("evt-001");
    }

    // ─── Balance Tests ────────────────────────────────────────────

    @Test
    @DisplayName("getBalance — returns computed balance from repository")
    void getBalance_existingAccount_returnsBalance() {
        when(transactionRepository.computeBalance("acct-123")).thenReturn(new BigDecimal("750.00"));

        BigDecimal balance = accountService.getBalance("acct-123");

        assertEquals(new BigDecimal("750.00"), balance);
        verify(transactionRepository).computeBalance("acct-123");
    }

    @Test
    @DisplayName("getBalance — returns zero for account with no transactions")
    void getBalance_unknownAccount_returnsZero() {
        when(transactionRepository.computeBalance("acct-unknown")).thenReturn(BigDecimal.ZERO);

        BigDecimal balance = accountService.getBalance("acct-unknown");

        assertEquals(BigDecimal.ZERO, balance);
    }

    // ─── Account Details Tests ────────────────────────────────────

    @Test
    @DisplayName("getAccountDetails — returns balance, count, and recent transactions")
    void getAccountDetails_returnsFullDetails() {
        when(transactionRepository.computeBalance("acct-123")).thenReturn(new BigDecimal("500.00"));
        when(transactionRepository.countByAccountId("acct-123")).thenReturn(3L);
        when(transactionRepository.findByAccountIdOrderByEventTimestampDesc("acct-123",
                PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(sampleTransaction)));

        AccountService.AccountDetails details = accountService.getAccountDetails("acct-123");

        assertEquals("acct-123", details.accountId());
        assertEquals(new BigDecimal("500.00"), details.balance());
        assertEquals(3L, details.totalTransactions());
        assertEquals(1, details.recentTransactions().size());
    }
}
