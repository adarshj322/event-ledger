package com.mphasis.eventledger.account.controller;

import com.mphasis.eventledger.account.dto.AccountDetailsResponse;
import com.mphasis.eventledger.account.dto.BalanceResponse;
import com.mphasis.eventledger.account.dto.TransactionRequest;
import com.mphasis.eventledger.account.dto.TransactionResponse;
import com.mphasis.eventledger.account.model.Transaction;
import com.mphasis.eventledger.account.model.TransactionType;
import com.mphasis.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the Account Service.
 * Manages account state — balances, transaction history, and account-level queries.
 */
@RestController
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * POST /accounts/{accountId}/transactions — Apply a transaction to an account.
     * Idempotent: duplicate eventIds return the existing transaction with 200 OK.
     */
    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {

        TransactionType type;
        try {
            type = TransactionType.valueOf(request.getType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown transaction type: '" + request.getType() + "'. Must be CREDIT or DEBIT.");
        }

        Transaction transaction = new Transaction(
                request.getEventId(),
                accountId,
                type,
                request.getAmount(),
                request.getCurrency(),
                request.getEventTimestamp()
        );

        AccountService.ApplyResult result = accountService.applyTransaction(transaction);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;

        log.info("Transaction {}: eventId={}, accountId={}, duplicate={}",
                result.duplicate() ? "acknowledged" : "applied",
                request.getEventId(), accountId, result.duplicate());

        return ResponseEntity.status(status)
                .body(TransactionResponse.fromEntity(result.transaction()));
    }

    /**
     * GET /accounts/{accountId}/balance — Get the current computed balance for an account.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        var balance = accountService.getBalance(accountId);
        log.info("Balance query: accountId={}, balance={}", accountId, balance);
        return ResponseEntity.ok(new BalanceResponse(accountId, balance, "USD"));
    }

    /**
     * GET /accounts/{accountId} — Get account details and recent transactions.
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountDetailsResponse> getAccountDetails(@PathVariable String accountId) {
        AccountService.AccountDetails details = accountService.getAccountDetails(accountId);
        log.info("Account details query: accountId={}, balance={}, txCount={}",
                accountId, details.balance(), details.totalTransactions());
        return ResponseEntity.ok(new AccountDetailsResponse(
                details.accountId(), details.balance(), "USD",
                details.totalTransactions(), details.recentTransactions()));
    }
}
