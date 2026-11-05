package org.elibanking.transactionservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Transfer OTPs (wallet-plan 3.4, D6). A code comes from {@link SecureRandom}, and only a keyed hash of it is kept in Redis.
 * The key is {@code OTP_HMAC_KEY}, which only transaction-service holds. It is not the internal service secret, which four
 * services share, and a six-digit code is easy to guess offline with the key and Redis read access. The hash is bound to the
 * transaction, so a code cannot be moved from one transfer to another.
 */
@Component
public class OtpCodes {

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration TTL = Duration.ofMinutes(5);
    public static final int MIN_KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public OtpCodes(@Value("${otp.hmac-key:}") String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        // Checked here, so the service does not start without a usable key. The message never includes the key.
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("OTP_HMAC_KEY must be set, and at least " + MIN_KEY_BYTES + " bytes long");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public static String codeKey(String transactionId) {
        return "verification:otp:" + transactionId;
    }

    public static String attemptsKey(String transactionId) {
        return "verification:otp-attempts:" + transactionId;
    }

    /** Set once per code by whichever outcome gets there first: success, the third wrong code, or expiry. */
    public static String decisionKey(String transactionId) {
        return "verification:otp-decided:" + transactionId;
    }

    /** What a claim decided. The name is stored with the claim time, so a recovery can finish the same outcome (wallet-plan 8.34). */
    public enum Outcome { COMPLETE, FLAG, EXPIRE }

    /** A stored claim: its outcome, and when it was made. */
    public record Decision(Outcome outcome, Instant at) {
    }

    /**
     * How long a decision is kept. It must outlast any crash window, because an abandoned claim is finished from this record.
     */
    public static final Duration DECISION_TTL = Duration.ofHours(24);

    public static String encodeDecision(Outcome outcome, Instant at) {
        return outcome.name() + "@" + at.toEpochMilli();
    }

    public static Decision decodeDecision(String stored) {
        int at = stored.indexOf('@');
        return new Decision(Outcome.valueOf(stored.substring(0, at)), Instant.ofEpochMilli(Long.parseLong(stored.substring(at + 1))));
    }

    /** A new six-digit code, 100000 to 999999. */
    public String generate() {
        return String.format("%06d", 100000 + random.nextInt(900000));
    }

    public String hash(String transactionId, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(("otp|" + transactionId + "|" + otp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares in constant time. A missing code never matches. */
    public boolean matches(String transactionId, String otp, String storedHash) {
        if (otp == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(transactionId, otp).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
