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
import java.util.HexFormat;

/**
 * Transfer OTPs (wallet-plan 3.4, D6). A code comes from {@link SecureRandom}, and only a keyed hash of it is kept in Redis.
 * The key is the internal service secret, which Redis does not hold, so someone who can read Redis cannot test codes offline.
 * The hash is bound to the transaction, so a code cannot be moved from one transfer to another.
 */
@Component
public class OtpCodes {

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration TTL = Duration.ofMinutes(5);

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public OtpCodes(@Value("${internal-auth.jwt-secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public static String codeKey(String transactionId) {
        return "verification:otp:" + transactionId;
    }

    public static String attemptsKey(String transactionId) {
        return "verification:otp-attempts:" + transactionId;
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
