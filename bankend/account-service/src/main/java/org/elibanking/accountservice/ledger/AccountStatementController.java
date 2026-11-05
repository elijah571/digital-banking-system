package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * The statement of one account (wallet-plan 3.2). Paged by keyset on the line id: pass {@code nextAfterLineId} back as
 * {@code afterLineId}. The running balance is computed by a window over the page. The owner or an ADMIN may read it.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountStatementController {

    static final int MAX_SIZE = 200;

    public record StatementLineView(long lineId, long entryId, String entryType, String reference, String direction,
                                    String amount, String runningBalance) {
    }

    /** @param nextAfterLineId the cursor for the next page, or null on the last page */
    public record StatementView(String accountNumber, String currency, String openingBalance,
                                List<StatementLineView> content, Long nextAfterLineId, int size) {
    }

    private final LedgerReader reader;

    public AccountStatementController(LedgerReader reader) {
        this.reader = reader;
    }

    @GetMapping("/{accountNumber}/statement")
    public StatementView statement(@PathVariable String accountNumber,
                                   @RequestParam(defaultValue = "0") long afterLineId,
                                   @RequestParam(defaultValue = "50") int size,
                                   Caller caller) {
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidPostingException("size must be between 1 and " + MAX_SIZE);
        }
        LedgerReader.AccountRow account = reader.accountRow(accountNumber)
                .orElseThrow(() -> new LedgerNotFoundException("no wallet for account " + accountNumber));
        if (!caller.canAccess(account.ownerId())) {
            throw new LedgerAccessDeniedException("this account belongs to another user");
        }
        BigDecimal opening = reader.balanceThrough(account.ledgerAccountId(), account.type(), afterLineId);
        List<LedgerReader.StatementRow> rows = reader.statementPage(account.ledgerAccountId(), account.type(), opening,
                afterLineId, size + 1);
        boolean more = rows.size() > size;
        List<LedgerReader.StatementRow> page = more ? rows.subList(0, size) : rows;
        Long next = more ? page.getLast().lineId() : null;
        String currency = account.currency();
        return new StatementView(accountNumber, currency, LedgerApi.money(opening, currency),
                page.stream().map(r -> new StatementLineView(r.lineId(), r.entryId(), r.entryType(), r.reference(),
                        r.direction(), LedgerApi.money(r.amount(), currency),
                        LedgerApi.money(r.runningBalance(), currency))).toList(),
                next, size);
    }
}
