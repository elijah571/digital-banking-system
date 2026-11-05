package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.audit.AuditLogWriter;
import org.elibanking.accountservice.idempotency.IdempotencyService;
import org.elibanking.accountservice.idempotency.StoredResponse;
import org.elibanking.accountservice.outbox.JsonStrings;
import org.elibanking.common.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Admin credit and deduct (wallet-plan 3.4, D5). Every adjustment is a ledger entry against ADJUSTMENT_EQUITY, with a reason
 * and an audit row. The posting, the audit row and the wallet mirror commit together. The idempotency key makes a retry safe:
 * the ledger key is {@code adm-{Idempotency-Key}}.
 */
@Service
public class AdminAdjustments {

    public enum Direction {
        CREDIT,
        DEDUCT
    }

    /** The outcome of one adjustment: the HTTP status to return and the JSON body. */
    public record Result(int status, String bodyJson) {
    }

    private static final Duration LEASE = Duration.ofSeconds(30);

    private final LedgerPostingService ledger;
    private final LedgerReader reader;
    private final IdempotencyService idempotency;
    private final AuditLogWriter audit;
    private final TransactionTemplate tx;

    public AdminAdjustments(LedgerPostingService ledger, LedgerReader reader, IdempotencyService idempotency,
                            AuditLogWriter audit, TransactionTemplate tx) {
        this.ledger = ledger;
        this.reader = reader;
        this.idempotency = idempotency;
        this.audit = audit;
        this.tx = tx;
    }

    public Result adjust(String accountNumber, Direction direction, String amount, String reason,
                         String idempotencyKey, String adminId) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidPostingException("reason is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidPostingException("Idempotency-Key is required");
        }
        LedgerReader.AccountRow account = reader.accountRow(accountNumber)
                .orElseThrow(() -> new LedgerNotFoundException("no wallet for account " + accountNumber));
        Money money = LedgerReader.parseAmount(amount, account.currency());
        long adjustmentEquity = reader.systemAccountOf("ADJUSTMENT_EQUITY:" + account.currency())
                .orElseThrow(() -> new LedgerNotFoundException("no ADJUSTMENT_EQUITY account for " + account.currency()));

        String scope = "admin:" + adminId + "/adjustments";
        String hash = IdempotencyService.hash(direction + "|" + accountNumber + "|" + money.toPlainString() + "|" + reason);
        Optional<StoredResponse> replay = idempotency.begin(scope, idempotencyKey, hash, LEASE);
        if (replay.isPresent()) {
            return new Result(replay.get().status(), replay.get().bodyJson());
        }

        PostingResult posted = tx.execute(status -> {
            List<PostingLine> lines = direction == Direction.CREDIT
                    ? List.of(new PostingLine(adjustmentEquity, Side.DEBIT, money),
                            new PostingLine(account.ledgerAccountId(), Side.CREDIT, money))
                    : List.of(new PostingLine(account.ledgerAccountId(), Side.DEBIT, money),
                            new PostingLine(adjustmentEquity, Side.CREDIT, money));
            PostingResult result = ledger.post(new PostingRequest("ADMIN_" + direction, "adm-" + idempotencyKey,
                    reason, idempotencyKey, adminId, lines, null));
            if (!result.replayed()) {
                audit.record(adminId, "ADMIN_" + direction, "ACCOUNT", accountNumber, idempotencyKey,
                        "{\"amount\":" + JsonStrings.quote(money.toPlainString()) + ",\"reason\":"
                                + JsonStrings.quote(reason) + ",\"entryId\":" + result.entryId() + "}");
            }
            return result;
        });

        String balance = reader.account(accountNumber).map(LedgerApi.AccountView::balance).orElse("");
        boolean replayed = posted.replayed();
        String body = "{\"accountNumber\":" + JsonStrings.quote(accountNumber)
                + ",\"direction\":" + JsonStrings.quote(direction.name())
                + ",\"amount\":" + JsonStrings.quote(money.toPlainString())
                + ",\"entryId\":" + posted.entryId()
                + ",\"replayed\":" + replayed
                + ",\"balance\":" + JsonStrings.quote(balance) + "}";
        int status = replayed ? 200 : 201;
        idempotency.complete(scope, idempotencyKey, status, body);
        return new Result(status, body);
    }
}
