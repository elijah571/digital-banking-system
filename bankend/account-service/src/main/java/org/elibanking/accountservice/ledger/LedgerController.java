package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The internal ledger API (wallet-plan 3.2). Service tokens only: {@code /internal/v1/**} is restricted to the SERVICE role,
 * and the legacy path never reaches it (8.18). The gateway does not route {@code /internal}.
 */
@RestController
@RequestMapping("/internal/v1/ledger")
public class LedgerController {

    private final LedgerPostingService ledger;
    private final LedgerReader reader;

    public LedgerController(LedgerPostingService ledger, LedgerReader reader) {
        this.ledger = ledger;
        this.reader = reader;
    }

    /** 201 when the entry is new, 200 when the idempotency key had already been posted (nothing new written). */
    @PostMapping("/entries")
    public ResponseEntity<LedgerApi.PostResponse> post(@RequestBody LedgerApi.PostEntryRequest request) {
        List<PostingLine> lines = request.lines().stream().map(this::line).toList();
        PostingResult result = ledger.post(new PostingRequest(request.type(), request.idempotencyKey(),
                request.reference(), request.correlationId(), request.createdBy(), lines, null));
        return respond(result);
    }

    @PostMapping("/entries/{entryId}/reverse")
    public ResponseEntity<LedgerApi.PostResponse> reverse(@PathVariable long entryId,
                                                          @RequestBody LedgerApi.ReverseRequest request) {
        return respond(ledger.reverse(entryId, request.idempotencyKey(), request.createdBy()));
    }

    @GetMapping("/entries/by-key/{idempotencyKey}")
    public LedgerApi.EntryView entryByKey(@PathVariable String idempotencyKey) {
        return reader.entryByKey(idempotencyKey)
                .orElseThrow(() -> new LedgerNotFoundException("no entry for idempotency key " + idempotencyKey));
    }

    @GetMapping("/entries/{entryId}")
    public LedgerApi.EntryView entry(@PathVariable long entryId) {
        return reader.entryById(entryId)
                .orElseThrow(() -> new LedgerNotFoundException("journal entry " + entryId + " not found"));
    }

    @GetMapping("/accounts/{accountNumber}")
    public LedgerApi.AccountView account(@PathVariable String accountNumber) {
        return reader.account(accountNumber)
                .orElseThrow(() -> new LedgerNotFoundException("no wallet for account " + accountNumber));
    }

    private PostingLine line(LedgerApi.LineRequest line) {
        boolean byNumber = line.accountNumber() != null;
        boolean bySystem = line.systemAccount() != null;
        if (byNumber == bySystem) {
            throw new InvalidPostingException("name each line with exactly one of accountNumber or systemAccount");
        }
        long accountId = byNumber
                ? reader.ledgerAccountOf(line.accountNumber())
                        .orElseThrow(() -> new LedgerNotFoundException("no wallet for account " + line.accountNumber()))
                : reader.systemAccountOf(line.systemAccount())
                        .orElseThrow(() -> new LedgerNotFoundException("no system account " + line.systemAccount()));
        Money amount = LedgerReader.parseAmount(line.amount(), line.currency());
        return new PostingLine(accountId, parseSide(line.side()), amount);
    }

    private static Side parseSide(String side) {
        if (side == null) {
            throw new InvalidPostingException("side is required (DEBIT or CREDIT)");
        }
        try {
            return Side.valueOf(side.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidPostingException("side must be DEBIT or CREDIT");
        }
    }

    private static ResponseEntity<LedgerApi.PostResponse> respond(PostingResult result) {
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new LedgerApi.PostResponse(result.entryId(), result.replayed()));
    }
}
