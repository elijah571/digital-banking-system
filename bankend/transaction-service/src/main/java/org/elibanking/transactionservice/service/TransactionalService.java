package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.exception.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.transactionservice.client.AccountInfo;
import feign.FeignException;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.exception.InvalidOtpException;
import org.elibanking.transactionservice.exception.TransactionRejectedException;
import org.elibanking.transactionservice.outbox.OutboxWriter;
import org.elibanking.transactionservice.exception.TransactionAccessDeniedException;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.dto.TransactionResponse;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.event.TransactionCompleteEvent;
import org.elibanking.transactionservice.event.TransactionInitiatedEvent;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionalService {

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    /** Where a transfer's money waits between the hold and the settle or release (wallet-plan 3.4). */
    private static final String IN_FLIGHT = "TRANSFER_IN_FLIGHT:NGN";
    /** A transfer that has not been held after this long is one the request never finished: the recovery job picks it up. */
    private static final java.time.Duration RECOVERY_AGE = java.time.Duration.ofMinutes(2);

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final LedgerClient ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, Object> kafkaTemplate; // Fixed: removed duplicate declaration
    private final RedisTemplate<String, String> redisTemplate;
    private final OtpCodes otps;

    public TransactionResponse transfer(TransactionRequest request, Caller caller) {
        log.info("Start Transfer");
        if (request.getSenderAccountNumber().equals(request.getReceiverAccountNumber())) {
            throw new IllegalArgumentException("Sender and receiver accounts must be different");
        }
        if (request.getAmount().stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places");
        }
        AccountInfo sender = accountService.getAccount(request.getSenderAccountNumber());
        // Everyone — admins included — can only send money from accounts they own.
        if (!caller.isInternal() && !caller.userId().equals(sender.ownerId())) {
            throw new TransactionAccessDeniedException("You can only transfer from your own accounts");
        }
        AccountInfo receiver = accountService.getAccount(request.getReceiverAccountNumber());
        requireActive(sender, "sender");
        requireActive(receiver, "receiver");

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setSenderUserId(caller.userId());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(request.getType());
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        // The row and its initiated event commit together, before any money moves (wallet-plan 3.8). The event is published
        // by the relay only after the hold, so a refused transfer never reaches the fraud check.
        Transaction savedTransaction = tx.execute(status -> {
            Transaction saved = transactionRepository.save(transaction);
            outbox.append(TRANSACTION_INITIATED_TOPIC, saved.getId(), saved.getId(), new TransactionInitiatedEvent(
                    saved.getId(), saved.getSenderAccountNumber(), saved.getReceiverAccountNumber(),
                    saved.getAmount(), saved.getDescription()));
            return saved;
        });
        log.info("Transaction saved: {}", savedTransaction.getId());

        holdFunds(savedTransaction);
        return mapToResponse(savedTransaction);
    }

    /**
     * Holds a transfer's amount: sender to TRANSFER_IN_FLIGHT (wallet-plan 3.4). A refusal by the ledger (4xx) fails the transfer and
     * says why. Any other failure leaves the row PROCESSING and unheld, and the recovery job holds it later under the same key.
     */
    void holdFunds(Transaction transaction) {
        try {
            ledger.post(entry("TRANSFER_HOLD", transaction, "tx-" + transaction.getId() + "-hold",
                    account(transaction.getSenderAccountNumber(), "DEBIT", transaction.getAmount()),
                    system(IN_FLIGHT, "CREDIT", transaction.getAmount())));
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason("Hold refused by account-service (" + e.status() + ")");
                transactionRepository.save(transaction);
            }
            throw e;
        }
        transaction.setHeldAt(LocalDateTime.now());
        transactionRepository.save(transaction);
    }

    /**
     * Holds transfers that were committed but never held, for example after a crash between the row and the hold (wallet-plan 3.8).
     * A sender that is no longer active when the job runs fails the transfer instead.
     */
    public void holdStuckTransfers() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RECOVERY_AGE);
        for (Transaction transaction : transactionRepository
                .findTop50ByStatusAndHeldAtIsNullAndCreatedAtBefore(TransactionStatus.PROCESSING, cutoff)) {
            try {
                requireActive(accountService.getAccount(transaction.getSenderAccountNumber()), "sender");
                holdFunds(transaction);
                log.info("Recovered the hold for transaction {}", transaction.getId());
            } catch (TransactionRejectedException e) {
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason(e.getMessage());
                transactionRepository.save(transaction);
            } catch (RuntimeException e) {
                log.warn("Recovery of transaction {} failed, will retry: {}", transaction.getId(), e.getMessage());
            }
        }
    }

    public TransactionResponse getTransaction(String transactionId, Caller caller) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found " + transactionId));
        if (!caller.canAccess(transaction.getSenderUserId())) {
            throw new TransactionAccessDeniedException("You do not have access to transaction " + transactionId);
        }
        return mapToResponse(transaction);
    }

    /** Every transaction (ADMIN). */
    public List<TransactionResponse> getAllTransactions() {
        return transactionRepository.findAllByOrderByCreatedAtDesc().stream().map(this::mapToResponse).toList();
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber, Caller caller) {
        if (!caller.isInternal() && !caller.isAdmin()) {
            AccountInfo account = accountService.getAccount(accountNumber);
            if (!caller.userId().equals(account.ownerId())) {
                throw new TransactionAccessDeniedException("You do not have access to account " + accountNumber);
            }
        }
        return transactionRepository
                .findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public TransactionResponse verifyOTP(String transactionId, String otp, Caller caller) {
        log.info("OTP verification for transaction: {}", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));

        // Only the person who started the transfer may confirm it.
        if (!caller.isInternal() && !caller.userId().equals(transaction.getSenderUserId())) {
            throw new TransactionAccessDeniedException("Only the sender can verify this transaction");
        }

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("Transaction " + transactionId
                    + " is not awaiting OTP verification (status: " + transaction.getStatus() + ")");
        }

        String storedHash = redisTemplate.opsForValue().get(OtpCodes.codeKey(transactionId));

        if (storedHash == null) {
            log.warn("OTP expired for transaction: {}", transactionId);
            forgetOtp(transactionId);
            compensateTransaction(transaction, "OTP expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }

        if (otps.matches(transactionId, otp, storedHash)) {
            log.info("OTP verified - completing transaction: {}", transactionId);
            forgetOtp(transactionId);
            completeTransaction(transaction);
            return mapToResponse(transaction);
        }

        // A wrong code counts. The first two only get a 400 and the transfer waits. The third flags it, refunds it and blocks the account.
        Long attempts = redisTemplate.opsForValue().increment(OtpCodes.attemptsKey(transactionId));
        long count = attempts == null ? OtpCodes.MAX_ATTEMPTS : attempts;
        if (count == 1) {
            redisTemplate.expire(OtpCodes.attemptsKey(transactionId), OtpCodes.TTL);
        }
        if (count < OtpCodes.MAX_ATTEMPTS) {
            log.warn("Wrong OTP for transaction {} (attempt {} of {})", transactionId, count, OtpCodes.MAX_ATTEMPTS);
            throw new InvalidOtpException(OtpCodes.MAX_ATTEMPTS - count);
        }

        log.warn("Wrong OTP for transaction {} for the last time - blocking account and refunding", transactionId);
        forgetOtp(transactionId);
        blockAccountAndCompensate(transaction,
                "Wrong OTP entered - transaction cancelled, account blocked for security");
        return mapToResponse(transaction);
    }

    /** Removes the code and its attempt count. A code is single use, whatever the outcome. */
    private void forgetOtp(String transactionId) {
        redisTemplate.delete(OtpCodes.codeKey(transactionId));
        redisTemplate.delete(OtpCodes.attemptsKey(transactionId));
    }

    private void compensateTransaction(Transaction transaction, String reason) {
        log.info("Compensation - refunding sender: {} amount: {}",
                transaction.getSenderAccountNumber(), transaction.getAmount());

        // The held amount goes back to the sender. The key makes a repeat a no-op.
        ledger.post(entry("TRANSFER_RELEASE", transaction, "tx-" + transaction.getId() + "-release",
                system(IN_FLIGHT, "DEBIT", transaction.getAmount()),
                account(transaction.getSenderAccountNumber(), "CREDIT", transaction.getAmount())));

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason + " | Refunded at " + LocalDateTime.now());
        transactionRepository.save(transaction);

        Map<String, Object> refundEvent = new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", reason);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), refundEvent);
        log.info("Compensation completed for transaction: {}", transaction.getId());
    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {
        Map<String, Object> fraudEvent = new HashMap<>();
        fraudEvent.put("transactionId", transaction.getId());
        fraudEvent.put("accountNumber", transaction.getSenderAccountNumber());
        fraudEvent.put("reason", reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getId(), fraudEvent);
        log.warn("fraud.detected published - account: {} blocked", transaction.getSenderAccountNumber());

        compensateTransaction(transaction, reason);
    }

    private void completeTransaction(Transaction transaction) {
        // The receiver is credited from the amount held in TRANSFER_IN_FLIGHT. The key makes a repeat a no-op, and the callers
        // only settle a PROCESSING or PENDING_VERIFICATION transfer, so a completed one is never settled twice.
        ledger.post(entry("TRANSFER_SETTLE", transaction, "tx-" + transaction.getId() + "-settle",
                system(IN_FLIGHT, "DEBIT", transaction.getAmount()),
                account(transaction.getReceiverAccountNumber(), "CREDIT", transaction.getAmount())));
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        TransactionCompleteEvent completeEvent = new TransactionCompleteEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, transaction.getId(), completeEvent);
        log.info("COMPLETE - Transaction {} completed successfully", transaction.getId());
    }

    public void processCleanResult(String transactionId) {
        log.info("Processing clean fraud result for transaction: {}", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));

        if (transaction.getStatus() != TransactionStatus.PROCESSING) {
            log.warn("Transaction {} not PROCESSING - skipping", transactionId);
            return;
        }

        // Fixed: Clean fraud check should complete or progress the transaction, not refund it
        completeTransaction(transaction);
    }

    private void requireActive(AccountInfo account, String role) {
        if (!"ACTIVE".equals(account.status())) {
            throw new TransactionRejectedException("The " + role + " account is not active");
        }
    }

    private static LedgerClient.LedgerEntryRequest entry(String type, Transaction transaction, String key,
                                                         LedgerClient.LedgerLine... lines) {
        return new LedgerClient.LedgerEntryRequest(type, key, transaction.getReferenceNumber(),
                transaction.getId(), "transaction-service", List.of(lines));
    }

    private static LedgerClient.LedgerLine account(String accountNumber, String side, BigDecimal amount) {
        return new LedgerClient.LedgerLine(accountNumber, null, side, money(amount), "NGN");
    }

    private static LedgerClient.LedgerLine system(String code, String side, BigDecimal amount) {
        return new LedgerClient.LedgerLine(null, code, side, money(amount), "NGN");
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }

    private TransactionResponse mapToResponse(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(transaction.getReceiverAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setFailureReason(transaction.getFailureReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());
        return response;
    }
}
