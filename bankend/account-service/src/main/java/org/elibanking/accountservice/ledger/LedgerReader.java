package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Read-only views of the ledger for the internal API. It writes nothing; {@link LedgerPostingService} is the only writer.
 */
@Service
public class LedgerReader {

    private final JdbcTemplate jdbc;

    public LedgerReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The ledger account of a customer wallet, found by its account number. */
    public Optional<Long> ledgerAccountOf(String accountNumber) {
        return jdbc.queryForList("SELECT ledger_account_id FROM accounts WHERE account_number = ? AND ledger_account_id IS NOT NULL",
                        Long.class, accountNumber)
                .stream().findFirst();
    }

    /** A system ledger account, found by its code such as {@code PAYOUT_PAYABLE:NGN}. */
    public Optional<Long> systemAccountOf(String code) {
        return jdbc.queryForList("SELECT id FROM ledger_account WHERE owner_type = 'SYSTEM' AND code = ?", Long.class, code)
                .stream().findFirst();
    }

    public Optional<LedgerApi.AccountView> account(String accountNumber) {
        List<LedgerApi.AccountView> rows = jdbc.query("""
                        SELECT a.account_number, la.id, la.currency, la.balance
                        FROM accounts a JOIN ledger_account la ON la.id = a.ledger_account_id
                        WHERE a.account_number = ?
                        """,
                (rs, n) -> new LedgerApi.AccountView(rs.getString("account_number"), rs.getLong("id"),
                        rs.getString("currency"),
                        LedgerApi.money(rs.getBigDecimal("balance"), rs.getString("currency"))),
                accountNumber);
        return rows.stream().findFirst();
    }

    public Optional<LedgerApi.EntryView> entryByKey(String idempotencyKey) {
        return jdbc.queryForList("SELECT id FROM journal_entry WHERE idempotency_key = ?", Long.class, idempotencyKey)
                .stream().findFirst().map(this::entry);
    }

    public Optional<LedgerApi.EntryView> entryById(long entryId) {
        return jdbc.queryForList("SELECT id FROM journal_entry WHERE id = ?", Long.class, entryId)
                .stream().findFirst().map(this::entry);
    }

    private LedgerApi.EntryView entry(long entryId) {
        return jdbc.queryForObject("""
                        SELECT id, type, idempotency_key, reference, reverses_entry_id FROM journal_entry WHERE id = ?
                        """,
                (rs, n) -> {
                    List<LedgerApi.LineView> lines = jdbc.query("""
                                    SELECT ledger_account_id, direction, amount, currency FROM journal_line
                                    WHERE entry_id = ? ORDER BY id
                                    """,
                            (lrs, ln) -> new LedgerApi.LineView(lrs.getLong("ledger_account_id"),
                                    lrs.getString("direction"),
                                    LedgerApi.money(lrs.getBigDecimal("amount"), lrs.getString("currency")),
                                    lrs.getString("currency")),
                            entryId);
                    Object reverses = rs.getObject("reverses_entry_id");
                    return new LedgerApi.EntryView(rs.getLong("id"), rs.getString("type"),
                            rs.getString("idempotency_key"), rs.getString("reference"),
                            reverses == null ? null : ((Number) reverses).longValue(), lines);
                }, entryId);
    }

    /** Parses a JSON amount such as "1500.00" in the currency it names. Rejects anything that does not fit. */
    static Money parseAmount(String amount, String currency) {
        if (amount == null || amount.isBlank()) {
            throw new InvalidPostingException("amount is required");
        }
        try {
            return Money.positive(new BigDecimal(amount), currency == null ? "NGN" : currency);
        } catch (NumberFormatException e) {
            throw new InvalidPostingException("amount must be a decimal string such as \"1500.00\"");
        }
    }
}
