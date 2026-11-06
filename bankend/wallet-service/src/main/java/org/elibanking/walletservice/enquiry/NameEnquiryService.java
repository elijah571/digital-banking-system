package org.elibanking.walletservice.enquiry;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountHolder;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The name enquiry (wallet-plan 8.4): who holds an account, before money is sent to it. The name is returned only for an ACTIVE account
 * on our own bank, and for a found account at another bank. Each enquiry costs a provider call, so the rate limit is the control against
 * harvesting names, and it fails closed: when Redis cannot count, the enquiry is refused with 503.
 */
@Slf4j
@Service
public class NameEnquiryService {

    static final String RATE_KEY = "name-enquiry-rate:";
    static final Duration RATE_WINDOW = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The result of a name enquiry: the reference to present, the holder's name, and when the reference stops working. */
    public record Result(String ref, String accountName, Instant expiresAt) {
    }

    private final NameEnquiryRepository enquiries;
    private final AccountServiceClient accounts;
    private final PaymentServiceClient payments;
    private final StringRedisTemplate redis;
    private final String ownBankCode;
    private final long rateLimit;
    private final Duration ttl;

    public NameEnquiryService(NameEnquiryRepository enquiries, AccountServiceClient accounts, PaymentServiceClient payments,
                              StringRedisTemplate redis, @Value("${wallet.own-bank.code}") String ownBankCode,
                              @Value("${wallet.name-enquiry.rate-limit}") long rateLimit,
                              @Value("${wallet.name-enquiry.ttl}") Duration ttl) {
        this.enquiries = enquiries;
        this.accounts = accounts;
        this.payments = payments;
        this.redis = redis;
        this.ownBankCode = ownBankCode;
        this.rateLimit = rateLimit;
        this.ttl = ttl;
    }

    public Result enquire(String userId, String bankCode, String accountNumber) {
        limit(userId);
        String name = ownBankCode.equals(bankCode) ? ownBankName(accountNumber) : otherBankName(bankCode, accountNumber);
        NameEnquiry enquiry = new NameEnquiry();
        enquiry.setRef(newRef());
        enquiry.setUserId(userId);
        enquiry.setBankCode(bankCode);
        enquiry.setAccountNumber(accountNumber);
        enquiry.setAccountName(name);
        enquiry.setCreatedAt(Instant.now());
        enquiry.setExpiresAt(enquiry.getCreatedAt().plus(ttl));
        enquiries.save(enquiry);
        return new Result(enquiry.getRef(), enquiry.getAccountName(), enquiry.getExpiresAt());
    }

    /**
     * Checks a reference for a transfer or a withdrawal: it must exist, belong to the caller, be unexpired, and match the bank and the
     * account number. Any failure is the same refusal, so the response does not reveal which check failed.
     */
    public NameEnquiry validate(String userId, String ref, String bankCode, String accountNumber) {
        NameEnquiry enquiry = ref == null ? null : enquiries.findById(ref).orElse(null);
        boolean valid = enquiry != null
                && enquiry.getUserId().equals(userId)
                && enquiry.getExpiresAt().isAfter(Instant.now())
                && enquiry.getBankCode().equals(bankCode)
                && enquiry.getAccountNumber().equals(accountNumber);
        if (!valid) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "Name enquiry is invalid or expired");
        }
        return enquiry;
    }

    private String ownBankName(String accountNumber) {
        AccountHolder holder = accounts.holder(accountNumber);
        if (!"ACTIVE".equals(holder.status())) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "This account is not active");
        }
        return holder.name();
    }

    private String otherBankName(String bankCode, String accountNumber) {
        PaymentServiceClient.Resolution resolution = payments.resolveAccount(bankCode, accountNumber);
        return switch (resolution.status()) {
            case "FOUND" -> resolution.accountName();
            case "NOT_FOUND" -> throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No account was found for that bank and number");
            default -> throw new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Name enquiries are unavailable for that bank right now. Try again shortly.");
        };
    }

    /** Counts this enquiry against the user's window. Redis that cannot count refuses the enquiry (fail closed). */
    private void limit(String userId) {
        Long count;
        try {
            count = redis.opsForValue().increment(RATE_KEY + userId);
            if (count != null && count == 1L) {
                redis.expire(RATE_KEY + userId, RATE_WINDOW);
            }
        } catch (DataAccessException unreachable) {
            log.warn("Name enquiry refused: the rate limit could not be checked ({})", unreachable.getClass().getSimpleName());
            throw new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Name enquiries are unavailable right now. Try again shortly.");
        }
        if (count != null && count > rateLimit) {
            throw new RequestRefusedException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many name enquiries. Try again in a few minutes.");
        }
    }

    private static String newRef() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
