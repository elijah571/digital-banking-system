package org.elibanking.transactionservice.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.dto.TransactionResponse;
import org.elibanking.transactionservice.exception.TransactionRejectedException;
import org.elibanking.transactionservice.idempotency.IdempotencyService;
import org.elibanking.transactionservice.idempotency.IdempotencyService.Begin;
import org.elibanking.transactionservice.idempotency.IdempotencyService.StoredResponse;
import org.elibanking.transactionservice.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * POST /api/v1/transactions with an Idempotency-Key (wallet-plan 3.6). A repeat with the same key and body returns what the first
 * attempt got, and moves no money. A repeat with a different body is refused. A repeat during the first attempt is refused until
 * its lease ends.
 */
@Service
@RequiredArgsConstructor
public class IdempotentTransfers {

    static final String ROUTE = "POST /api/v1/transactions";
    /** Long enough for one transfer, and short enough that a crashed attempt does not hold the key for long. */
    static final Duration LEASE = Duration.ofSeconds(60);

    private final IdempotencyService idempotency;
    private final TransactionalService transfers;
    private final WalletTransferClient wallet;
    private final ObjectMapper json;
    @Value("${transfer.own-bank-code}")
    private String ownBankCode;

    public record ExternalReply(int status, String body) {
    }

    public boolean isExternalBank(String bankCode) {
        return bankCode != null && !bankCode.isBlank() && !ownBankCode.equals(bankCode);
    }

    public TransactionResponse transfer(String key, TransactionRequest request, Caller caller) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The Idempotency-Key header is required");
        }
        String scope = (caller.userId() == null ? "internal" : caller.userId()) + " " + ROUTE;
        Begin begin = idempotency.begin(scope, key, IdempotencyService.hash(canonical(request)), LEASE);
        if (begin.stored() != null) {
            return replay(begin.stored());
        }

        AtomicReference<String> linked = new AtomicReference<>();
        try {
            if (request.getNameEnquiryRef() != null && !request.getNameEnquiryRef().isBlank()) {
                if (caller.userId() == null) {
                    throw new IllegalArgumentException("A name-enquiry reference requires an authenticated user");
                }
                wallet.validate(caller.userId(), request.getNameEnquiryRef(), ownBankCode, request.getReceiverAccountNumber());
            }
            TransactionResponse response = begin.transactionId() == null
                    ? transfers.transfer(request, caller, id -> {
                        idempotency.recordTransaction(scope, key, id);
                        linked.set(id);
                    })
                    : transfers.resume(begin.transactionId());
            idempotency.complete(scope, key, 201, json.writeValueAsString(response));
            return response;
        } catch (TransactionRejectedException e) {
            // A refusal is an answer too: a repeat gets the same refusal, and moves no money.
            idempotency.complete(scope, key, 422, json.writeValueAsString(Map.of("detail", e.getMessage())));
            throw e;
        } catch (FeignException e) {
            // The ledger refused the hold after the transfer was saved. TransactionalService marks it FAILED with this reason.
            boolean transferExists = begin.transactionId() != null || linked.get() != null;
            if (transferExists && e.status() >= 400 && e.status() < 500) {
                idempotency.complete(scope, key, 422, json.writeValueAsString(Map.of("detail",
                        "Hold refused by account-service (" + e.status() + ")")));
            }
            throw e;
        } catch (RuntimeException e) {
            // Nothing was saved, so a retry may start again at once. A saved transfer keeps the record, and the retry resumes it.
            if (begin.transactionId() == null && linked.get() == null) {
                idempotency.release(scope, key);
            }
            throw e;
        }
    }

    /** Routes and durably replays an external transfer while wallet's own idempotency protects the payout resource. */
    public ExternalReply externalTransfer(String key, TransactionRequest request, Caller caller) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The Idempotency-Key header is required");
        }
        if (request.getNameEnquiryRef() == null || request.getNameEnquiryRef().isBlank()) {
            throw new WalletTransferRejectedException(422, "A valid name-enquiry reference is required for another bank");
        }
        if (request.getAmount().stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places");
        }
        String scope = (caller.userId() == null ? "internal" : caller.userId()) + " " + ROUTE;
        Begin begin = idempotency.begin(scope, key, IdempotencyService.hash(canonical(request)), LEASE);
        if (begin.stored() != null) {
            return new ExternalReply(begin.stored().status(), begin.stored().body());
        }
        try {
            if (caller.userId() == null) {
                throw new IllegalArgumentException("An external transfer needs an authenticated user");
            }
            WalletTransferClient.Name destination = wallet.validate(caller.userId(), request.getNameEnquiryRef(),
                    request.getBankCode(), request.getReceiverAccountNumber());
            WalletTransferClient.Reply reply = wallet.transfer(key, caller.userId(), request.getSenderAccountNumber(),
                    request.getReceiverAccountNumber(), destination.accountName(), request.getAmount().setScale(2).toPlainString(),
                    request.getBankCode(), request.getNameEnquiryRef(), request.getDescription(), request.getPin());
            idempotency.complete(scope, key, reply.status(), reply.body() == null ? "{}" : reply.body());
            return new ExternalReply(reply.status(), reply.body());
        } catch (RuntimeException failure) {
            idempotency.release(scope, key);
            throw failure;
        }
    }

    private TransactionResponse replay(StoredResponse stored) {
        if (stored.status() == 201) {
            return json.readValue(stored.body(), TransactionResponse.class);
        }
        throw new TransactionRejectedException(json.readTree(stored.body()).get("detail").asString());
    }

    /** The fields that define the transfer, in a fixed order. Two requests with the same canonical form are the same request. */
    static String canonical(TransactionRequest request) {
        return String.join("|", request.getSenderAccountNumber(), request.getReceiverAccountNumber(),
                request.getAmount().stripTrailingZeros().toPlainString(), String.valueOf(request.getType()),
                request.getDescription() == null ? "" : request.getDescription(),
                request.getBankCode() == null ? "" : request.getBankCode(),
                request.getNameEnquiryRef() == null ? "" : request.getNameEnquiryRef());
    }
}
