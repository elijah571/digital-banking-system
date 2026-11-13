package org.elibanking.transactionservice.contrller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.dto.TransactionResponse;
import org.elibanking.transactionservice.exception.TransactionAccessDeniedException;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.service.IdempotentTransfers;
import org.elibanking.transactionservice.service.TransactionalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/transactions")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Money transfers (saga with fraud check) and OTP verification")
public class TransactionController {

    private final TransactionalService transactionalService;
    private final IdempotentTransfers idempotentTransfers;
    private final ObjectMapper json;

    @PostMapping
    @Operation(summary = "Transfer money",
            description = """
                    Sends money **from one of your own accounts**. Debits the sender immediately and returns the transaction with status **PROCESSING**.
                    If `bankCode` is another bank's code, include a recent `nameEnquiryRef` from `POST /api/v1/transfers/name-enquiry`; the request opens an external payout through wallet-service and returns **202** with its payout status. An omitted or own-bank `bankCode` keeps the internal transfer flow, and a name-enquiry reference is optional there.
                    The fraud-detection service then checks it asynchronously (usually < 1 s):

                    * **clean** → status becomes **COMPLETED** and the receiver is credited
                    * **suspicious** (> 5 transfers/min, amount > 5× the account's average, or > 90 % of balance)
                      → status becomes **PENDING_VERIFICATION** and an OTP is sent (valid 5 minutes).
                      Confirm with `POST /api/v1/transactions/{id}/verify`.

                    Poll `GET /api/v1/transactions/{id}` to follow the status.""")
    @ApiResponse(responseCode = "201", description = "Transfer accepted, status PROCESSING")
    @ApiResponse(responseCode = "202", description = "External bank payout accepted; poll /api/v1/wallet/payouts/{id}")
    @ApiResponse(responseCode = "400", description = "Validation failed (missing account numbers, non-positive amount, missing type)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Sender account is not yours",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Sender account not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The same Idempotency-Key is still being processed; retry later",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Insufficient funds, sender account not active, or the Idempotency-Key was used for a different request",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Account service unavailable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> transfer(
            @Parameter(description = "Unique per transfer. A repeat with the same key and body returns the first result and moves no money.",
                    required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransactionRequest request, Caller caller
    ) {
        if (idempotentTransfers.isExternalBank(request.getBankCode())) {
            IdempotentTransfers.ExternalReply reply = idempotentTransfers.externalTransfer(idempotencyKey, request, caller);
            return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(json.readTree(reply.body()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotentTransfers.transfer(idempotencyKey, request, caller));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a transaction",
            description = "Status is one of PROCESSING, PENDING_VERIFICATION, COMPLETED, FLAGGED.")
    @ApiResponse(responseCode = "200", description = "Transaction found")
    @ApiResponse(responseCode = "403", description = "Transaction belongs to another user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<TransactionResponse> getTransaction(
            @Parameter(description = "Transaction id (UUID)", example = "3698340e-3947-4615-a4fd-fd903a1a436e")
            @PathVariable String transactionId, Caller caller
    ) {
        return ResponseEntity.ok(transactionalService.getTransaction(transactionId, caller));
    }

    @GetMapping
    @Operation(summary = "List all transactions (ADMIN)", description = "Every transaction, newest first.")
    @ApiResponse(responseCode = "200", description = "All transactions")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    public List<TransactionResponse> getAllTransactions(Caller caller) {
        if (!caller.isAdmin()) {
            throw new TransactionAccessDeniedException("Admin role required");
        }
        return transactionalService.getAllTransactions();
    }

    @GetMapping("/account/{accountNumber}")
    @Operation(summary = "Transaction history of an account",
            description = "Transfers **sent** by this account, newest first. Empty list if there are none.")
    @ApiResponse(responseCode = "200", description = "List of transactions (may be empty)")
    @ApiResponse(responseCode = "403", description = "Account belongs to another user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<List<TransactionResponse>> getTransactionHistory(
            @Parameter(description = "10-digit account number", example = "3693612260") @PathVariable String accountNumber,
            Caller caller
    ) {
        return ResponseEntity.ok(transactionalService.getTransactionHistory(accountNumber, caller));
    }

    @PostMapping("/{transactionId}/verify")
    @Operation(summary = "Verify OTP for a flagged transfer",
            description = """
                    Only for transactions in **PENDING_VERIFICATION**.

                    * correct OTP → **COMPLETED**, receiver credited
                    * wrong OTP, first or second attempt → **400** (`Incorrect OTP. N attempts left`), still PENDING_VERIFICATION
                    * wrong OTP, third attempt → **FLAGGED**, sender refunded and sender account **BLOCKED**
                    * OTP expired (5 min) → **EXPIRED**, sender refunded. A transfer whose code is never sent is also **EXPIRED** by the recovery job, once it is older than the code's lifetime plus five minutes.

                    The OTP is delivered by notification-service (currently written to its log).""")
    @ApiResponse(responseCode = "200", description = "Verification processed — check `status` in the body")
    @ApiResponse(responseCode = "400", description = "Wrong OTP, with attempts left (the transfer stays PENDING_VERIFICATION)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Only the sender can verify",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Transaction is not awaiting OTP verification (e.g. already verified)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<TransactionResponse> verifyOTP(
            @Parameter(description = "Transaction id (UUID)") @PathVariable String transactionId,
            @Parameter(description = "6-digit OTP", example = "403826") @RequestParam String otp,
            Caller caller
    ) {
        return ResponseEntity.ok(
                transactionalService.verifyOTP(transactionId, otp, caller)
        );
    }
}
