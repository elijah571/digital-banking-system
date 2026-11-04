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

    /** A customer account with its owner and its wallet's account type, for ownership checks and statements. */
    public record AccountRow(String accountNumber, String ownerId, long ledgerAccountId, String currency, String type) {
    }

    /** One line of a statement, with the balance after it. */
    public record StatementRow(long lineId, long entryId, String entryType, String reference, String direction,
                               BigDecimal amount, String currency, BigDecimal runningBalance) {
    }

    public Optional<AccountRow> accountRow(String accountNumber) {
        return jdbc.query("""
                        SELECT a.account_number, a.owner_id, a.ledger_account_id, a.currency, la.type
                        FROM accounts a JOIN ledger_account la ON la.id = a.ledger_account_id
                        WHERE a.account_number = ?
                        """,
                (rs, n) -> new AccountRow(rs.getString("account_number"), rs.getString("owner_id"),
                        rs.getLong("ledger_account_id"), rs.getString("currency"), rs.getString("type")),
                accountNumber).stream().findFirst();
    }

    /**
     * The balance after every line up to and including {@code afterLineId}, on the account's normal side. Zero when
     * {@code afterLineId} is 0.
     */
    public BigDecimal balanceThrough(long ledgerAccountId, String type, long afterLineId) {
        BigDecimal balance = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(CASE WHEN ? IN ('ASSET', 'EXPENSE')
                                 THEN CASE WHEN jl.direction = 'DEBIT' THEN jl.amount ELSE -jl.amount END
                                 ELSE CASE WHEN jl.direction = 'CREDIT' THEN jl.amount ELSE -jl.amount END END), 0)
                        FROM journal_line jl WHERE jl.ledger_account_id = ? AND jl.id <= ?
                        """, BigDecimal.class, type, ledgerAccountId, afterLineId);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    /**
     * One page of an account's lines, from the line after {@code afterLineId}. The running balance is a window SUM over the
     * page, starting from the opening balance, so the window only touches the rows on this page.
     */
    public List<StatementRow> statementPage(long ledgerAccountId, String type, BigDecimal opening, long afterLineId,
                                            int limit) {
        return jdbc.query("""
                        SELECT p.id, p.entry_id, je.type, je.reference, p.direction, p.amount, p.currency,
                               CAST(? AS DECIMAL(19, 4)) + SUM(CASE WHEN ? IN ('ASSET', 'EXPENSE')
                                   THEN CASE WHEN p.direction = 'DEBIT' THEN p.amount ELSE -p.amount END
                                   ELSE CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END END)
                                   OVER (ORDER BY p.id) AS running_balance
                        FROM (SELECT id, entry_id, direction, amount, currency FROM journal_line
                              WHERE ledger_account_id = ? AND id > ? ORDER BY id LIMIT ?) p
                        JOIN journal_entry je ON je.id = p.entry_id
                        ORDER BY p.id
                        """,
                (rs, n) -> new StatementRow(rs.getLong("id"), rs.getLong("entry_id"), rs.getString("type"),
                        rs.getString("reference"), rs.getString("direction"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getBigDecimal("running_balance")),
                opening, type, ledgerAccountId, afterLineId, limit);
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
