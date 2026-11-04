package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * The JSON shapes of the internal ledger API (wallet-plan 3.2). Money travels as strings, such as "1500.00" (D3).
 */
public final class LedgerApi {

    private LedgerApi() {
    }

    /**
     * One line of a posting. Name the account by {@code accountNumber} (a customer wallet), or by {@code systemAccount}
     * (a system account code such as {@code PAYOUT_PAYABLE:NGN}). Exactly one of the two.
     */
    public record LineRequest(String accountNumber, String systemAccount, String side, String amount, String currency) {
    }

    public record PostEntryRequest(String type, String idempotencyKey, String reference, String correlationId,
                                   String createdBy, List<LineRequest> lines) {
    }

    public record ReverseRequest(String idempotencyKey, String createdBy) {
    }

    public record LineView(long ledgerAccountId, String side, String amount, String currency) {
    }

    public record EntryView(long entryId, String type, String idempotencyKey, String reference,
                            Long reversesEntryId, List<LineView> lines) {
    }

    /** @param replayed true when the idempotency key had been used already and nothing new was written */
    public record PostResponse(long entryId, boolean replayed) {
    }

    public record AccountView(String accountNumber, long ledgerAccountId, String currency, String balance) {
    }

    /** The amount at the currency's scale, such as "95.00" for NGN. The database keeps four decimals. */
    static String money(BigDecimal amount, String currency) {
        return Money.of(amount, currency).toPlainString();
    }
}
