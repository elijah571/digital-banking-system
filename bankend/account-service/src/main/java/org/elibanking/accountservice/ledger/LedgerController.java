package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The internal ledger API (wallet-plan 3.2). Service tokens only, and each route accepts only the services named in
 * {@link org.elibanking.accountservice.security.CallerAllowList} (wallet-plan 3.7). The gateway does not route {@code /internal}.
 *
 * <p>Every new entry writes its audit row in the same transaction ({@link ServicePostings}). The actor is the token's
 * {@code sub}, and the correlation id is {@code X-Request-Id}: taken from the request, or generated when it is missing.
 */
@RestController
@RequestMapping("/internal/v1/ledger")
public class LedgerController {

    static final String REQUEST_ID = "X-Request-Id";
    private static final int REQUEST_ID_MAX_LENGTH = 100;

    private final ServicePostings postings;
    private final LedgerReader reader;

    public LedgerController(ServicePostings postings, LedgerReader reader) {
        this.postings = postings;
        this.reader = reader;
    }

    /** 201 when the entry is new, 200 when the idempotency key had already been posted (nothing new written). */
    @PostMapping("/entries")
    public ResponseEntity<LedgerApi.PostResponse> post(@RequestBody LedgerApi.PostEntryRequest request,
                                                       @AuthenticationPrincipal Jwt caller,
                                                       @RequestHeader(name = REQUEST_ID, required = false) String header) {
        String requestId = requestId(header);
        List<PostingLine> lines = request.lines().stream().map(this::line).toList();
        PostingResult result = postings.post(new PostingRequest(request.type(), request.idempotencyKey(),
                request.reference(), request.correlationId(), request.createdBy(), lines, null),
                caller.getSubject(), requestId);
        return respond(result, requestId);
    }

    @PostMapping("/entries/{entryId}/reverse")
    public ResponseEntity<LedgerApi.PostResponse> reverse(@PathVariable long entryId,
                                                          @RequestBody LedgerApi.ReverseRequest request,
                                                          @AuthenticationPrincipal Jwt caller,
                                                          @RequestHeader(name = REQUEST_ID, required = false) String header) {
        String requestId = requestId(header);
        PostingResult result = postings.reverse(entryId, request.idempotencyKey(), request.createdBy(),
                caller.getSubject(), requestId);
        return respond(result, requestId);
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

    /** The caller's X-Request-Id, or a new one when it is missing. It is the audit row's correlation id (at most 100). */
    static String requestId(String header) {
        if (header == null || header.isBlank()) {
            return UUID.randomUUID().toString();
        }
        boolean visibleAscii = header.chars().allMatch(c -> c > 0x20 && c < 0x7f);
        if (!visibleAscii || header.length() > REQUEST_ID_MAX_LENGTH) {
            throw new InvalidPostingException("X-Request-Id must be 1 to " + REQUEST_ID_MAX_LENGTH
                    + " visible ASCII characters");
        }
        return header;
    }

    private static ResponseEntity<LedgerApi.PostResponse> respond(PostingResult result, String requestId) {
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).header(REQUEST_ID, requestId)
                .body(new LedgerApi.PostResponse(result.entryId(), result.replayed()));
    }
}
