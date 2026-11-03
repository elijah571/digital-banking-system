package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.exception.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
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
import org.springframework.stereotype.Service;

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

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final KafkaTemplate<String, Object> kafkaTemplate; // Fixed: removed duplicate declaration
    private final RedisTemplate<String, String> redisTemplate;

    public TransactionResponse transfer(TransactionRequest request, Caller caller) {
        log.info("Start Transfer");
        if (request.getSenderAccountNumber().equals(request.getReceiverAccountNumber())) {
            throw new IllegalArgumentException("Sender and receiver accounts must be different");
        }
        // Everyone — admins included — can only send money from accounts they own.
        if (!caller.isInternal()) {
            AccountInfo sender = accountService.getAccount(request.getSenderAccountNumber());
            if (!caller.userId().equals(sender.ownerId())) {
                throw new TransactionAccessDeniedException("You can only transfer from your own accounts");
            }
        }

        // Deduct balance from sender via Feign client
        accountService.deductBalance(
                request.getSenderAccountNumber(),
                request.getAmount()
        );

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setSenderUserId(caller.userId());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(request.getType());
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved: {}", savedTransaction.getId());

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);
        log.info("Transaction initiated published: {}", savedTransaction.getId());

        return mapToResponse(savedTransaction);
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

        String otpKey = "verification:otp:" + transactionId;
        String storedOtp = redisTemplate.opsForValue().get(otpKey);

        if (storedOtp == null) {
            log.warn("OTP expired for transaction: {}", transactionId);
            compensateTransaction(transaction, "OTP expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }

        // Fixed: Correct logic for matching OTP
        if (!storedOtp.equals(otp)) {
            log.warn("Wrong OTP entered - blocking account and refunding: {}", transactionId);
            redisTemplate.delete(otpKey);
            blockAccountAndCompensate(transaction,
                    "Wrong OTP entered - transaction cancelled, account blocked for security");
            return mapToResponse(transaction);
        }

        // OTP is correct
        log.info("OTP verified - completing transaction: {}", transactionId);
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);
    }

    private void compensateTransaction(Transaction transaction, String reason) {
        log.info("Compensation - refunding sender: {} amount: {}",
                transaction.getSenderAccountNumber(), transaction.getAmount());

        accountService.creditBalance(
                transaction.getSenderAccountNumber(),
                transaction.getAmount()
        );

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
