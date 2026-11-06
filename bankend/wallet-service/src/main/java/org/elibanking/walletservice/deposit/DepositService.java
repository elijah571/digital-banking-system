package org.elibanking.walletservice.deposit;

import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountCheck;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Intent;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.idempotency.IdempotencyService;
import org.elibanking.walletservice.idempotency.IdempotencyService.Begin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Opens a card deposit (wallet-plan 3.5, steps 1 and 2). The account must be the caller's and ACTIVE, and in NGN. The deposit
 * is inserted PENDING with its audit row and the idempotency link, in one transaction. Then payment-service creates the card
 * intent, outside any transaction, and the intent id is stored on the deposit.
 *
 * <p>A failure after the deposit is linked leaves the key's lease to expire at once, so a retry resumes that deposit and asks
 * payment-service again. payment-service keys the intent by the deposit id, so the retry gets the same intent.
 */
@Service
public class DepositService {

    static final String CURRENCY = "NGN";
    static final String ROUTE = "POST /api/v1/wallet/deposits";
    /** Judged on the database clock. A request that dies holds its key for this long at most. */
    static final Duration LEASE = Duration.ofSeconds(60);

    /** The status and JSON body to send, for a new response or a replay. */
    public record Reply(int status, String body) {
    }

    private final DepositRepository deposits;
    private final IdempotencyService idempotency;
    private final AccountServiceClient accounts;
    private final PaymentServiceClient payments;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BigDecimal cardMin;
    private final Duration expiry;

    public DepositService(DepositRepository deposits, IdempotencyService idempotency, AccountServiceClient accounts,
                          PaymentServiceClient payments, TransactionTemplate tx, JdbcTemplate jdbc, ObjectMapper mapper,
                          @Value("${wallet.deposits.card-min}") BigDecimal cardMin,
                          @Value("${wallet.deposits.expiry}") Duration expiry) {
        this.deposits = deposits;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.payments = payments;
        this.tx = tx;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cardMin = cardMin;
        this.expiry = expiry;
    }

    public Reply create(String userId, String key, DepositRequest request) {
        BigDecimal amount = new BigDecimal(request.amount());
        if (amount.compareTo(cardMin) < 0) {
            throw new RequestRefusedException(HttpStatus.BAD_REQUEST,
                    "A card deposit is at least " + cardMin.toPlainString() + " " + CURRENCY);
        }
        String scope = userId + " " + ROUTE;
        String requestHash = IdempotencyService.hash(request.accountNumber() + "|" + amount.toPlainString());
        Begin begin = idempotency.begin(scope, key, requestHash, LEASE);
        if (begin.stored() != null) {
            return new Reply(begin.stored().status(), begin.stored().body());
        }

        String depositId = begin.resourceId();
        if (depositId == null) {
            try {
                depositId = open(scope, key, userId, request, amount);
            } catch (RuntimeException e) {
                // Nothing was linked, so the key is released and a retry starts again.
                idempotency.release(scope, key);
                throw e;
            }
        }
        // The key now names a deposit. A failure below ends the lease, so a retry resumes this deposit.
        try {
            return finish(scope, key, depositId);
        } catch (RuntimeException e) {
            idempotency.expireLease(scope, key);
            throw e;
        }
    }

    /** Checks the account, then inserts the deposit, its audit row and the key link in one transaction. */
    private String open(String scope, String key, String userId, DepositRequest request, BigDecimal amount) {
        AccountCheck account = accounts.check(request.accountNumber());
        if (!userId.equals(account.ownerId())) {
            throw new RequestRefusedException(HttpStatus.FORBIDDEN, "This account belongs to another user");
        }
        if (!"ACTIVE".equals(account.status())) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "This account is not active");
        }
        if (!CURRENCY.equals(account.currency())) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "Card deposits are only accepted into NGN accounts");
        }
        return tx.execute(status -> {
            Deposit deposit = Deposit.pending(UUID.randomUUID().toString(), userId, request.accountNumber(), amount,
                    CURRENCY, Instant.now().plus(expiry));
            deposits.save(deposit);
            jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details)"
                            + " VALUES (?, 'DEPOSIT_REQUESTED', 'deposit', ?, JSON_OBJECT('amount', ?, 'currency', ?))",
                    userId, deposit.getId(), amount.toPlainString(), CURRENCY);
            idempotency.recordResource(scope, key, deposit.getId());
            return deposit.getId();
        });
    }

    /** Creates the card intent for a PENDING deposit, stores its id, and records the response for replay. */
    private Reply finish(String scope, String key, String depositId) {
        Deposit deposit = deposits.findById(depositId).orElseThrow(() ->
                new IllegalStateException("deposit " + depositId + " is missing for Idempotency-Key " + key));
        String intentId = deposit.getPaymentIntentId();
        String clientSecret = null;
        if (deposit.getStatus() == DepositStatus.PENDING) {
            Intent intent = payments.createIntent(depositId, deposit.getAmount().toPlainString(), deposit.getCurrency());
            deposit.setPaymentIntentId(intent.paymentIntentId());
            tx.executeWithoutResult(status -> deposits.save(deposit));
            intentId = intent.paymentIntentId();
            clientSecret = intent.clientSecret();
        }
        String body = mapper.writeValueAsString(new DepositResponse(depositId, deposit.getStatus().name(),
                deposit.getAccountNumber(), deposit.getAmount().toPlainString(), deposit.getCurrency(), intentId, clientSecret));
        idempotency.complete(scope, key, HttpStatus.CREATED.value(), body);
        return new Reply(HttpStatus.CREATED.value(), body);
    }
}
