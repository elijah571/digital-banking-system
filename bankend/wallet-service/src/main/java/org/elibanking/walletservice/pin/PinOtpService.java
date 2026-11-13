package org.elibanking.walletservice.pin;

import org.elibanking.common.events.KafkaEventEnvelope;
import org.elibanking.walletservice.exception.PinOtpCooldownException;
import org.elibanking.walletservice.exception.PinOtpUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * The six-digit code that authorises a PIN change (wallet-plan 3.5; threat model T2, T10 and D-P4, D-P11).
 *
 * <ul>
 *   <li><b>Only a keyed hash is stored</b>, under {@code PIN_OTP_HMAC_KEY}, a key of its own. Redis alone does not give the code.</li>
 *   <li><b>Five tries.</b> Each guess takes its place in the count with an atomic INCR before it is compared. Only the first five
 *       are compared. The fifth wrong guess ends the code. Later guesses are refused without a comparison.</li>
 *   <li><b>Single use.</b> A right code is claimed with SETNX before it is used, so two parallel right codes give one success.</li>
 *   <li><b>One code a minute</b> (D-P11), so a user cannot flood an inbox.</li>
 *   <li><b>Sent by email</b>, through the {@code notification.otp} topic with {@code purpose=PIN_CHANGE}. The code is never logged
 *       by this service.</li>
 * </ul>
 */
@Service
public class PinOtpService {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration COOLDOWN = Duration.ofMinutes(1);
    static final int MAX_ATTEMPTS = 5;
    static final String TOPIC = "notification.otp";

    public enum Result { VALID, WRONG, NONE, REFUSED }

    /** @param attemptsLeft the tries left after a WRONG answer; 0 once the code is ended */
    public record Attempt(Result result, int attemptsLeft) {
    }

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Object> kafka;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public PinOtpService(StringRedisTemplate redis, KafkaTemplate<String, Object> kafka, JdbcTemplate jdbc,
                         @Value("${pin.otp-hmac-key}") String key) {
        this.redis = redis;
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    /** Makes a new code, stores its hash, and sends it to the user's email. */
    public void issue(String userId, String email, String actor) {
        Boolean started = redis.opsForValue().setIfAbsent(cooldownKey(userId), "1", COOLDOWN);
        if (!Boolean.TRUE.equals(started)) {
            Long wait = redis.getExpire(cooldownKey(userId));
            throw new PinOtpCooldownException(wait == null || wait < 0 ? COOLDOWN.toSeconds() : wait);
        }

        String code = String.format("%06d", 100000 + random.nextInt(900000));
        redis.opsForValue().set(codeKey(userId), hash(userId, code), TTL);
        redis.opsForValue().set(attemptsKey(userId), "0", TTL);
        redis.delete(decidedKey(userId));

        Map<String, Object> event = Map.of(
                "purpose", "PIN_CHANGE",
                "userId", userId,
                "email", email,
                "otp", code,
                "reason", "PIN change");
        try {
            kafka.send(KafkaEventEnvelope.record(TOPIC, userId, event)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // The code cannot reach the user, so it must not wait: the next request may ask again at once.
            redis.delete(cooldownKey(userId));
            redis.delete(codeKey(userId));
            throw new PinOtpUnavailableException();
        }
        audit(actor, userId, "PIN_OTP_ISSUED", null);
    }

    /** Checks a code the user typed. The count comes first, so a guess past the fifth is refused without a comparison. */
    public Attempt consume(String userId, String otp, String actor) {
        Long count = redis.opsForValue().increment(attemptsKey(userId));
        if (count == null) {
            throw new IllegalStateException("Redis returned no count for the PIN code");
        }
        if (count == 1) {
            redis.expire(attemptsKey(userId), TTL);
        }
        if (count > MAX_ATTEMPTS) {
            audit(actor, userId, "PIN_OTP_REFUSED", null);
            return new Attempt(Result.REFUSED, 0);
        }

        String stored = redis.opsForValue().get(codeKey(userId));
        if (stored == null) {
            return new Attempt(Result.NONE, 0);
        }
        if (MessageDigest.isEqual(hash(userId, otp).getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8))) {
            // A right code is single use: whoever claims it first is the only one that succeeds.
            Boolean claimed = redis.opsForValue().setIfAbsent(decidedKey(userId), "USED", TTL);
            if (!Boolean.TRUE.equals(claimed)) {
                return new Attempt(Result.REFUSED, 0);
            }
            redis.delete(codeKey(userId));
            audit(actor, userId, "PIN_OTP_VERIFIED", null);
            return new Attempt(Result.VALID, 0);
        }

        // The stored code is kept on a wrong guess, even the fifth. The count ends the code, so every parallel guess that was
        // counted in time is answered the same way.
        audit(actor, userId, count < MAX_ATTEMPTS ? "PIN_OTP_WRONG" : "PIN_OTP_ENDED", null);
        return new Attempt(Result.WRONG, Math.max(0, MAX_ATTEMPTS - count.intValue()));
    }

    /** {@code HMAC-SHA256(PIN_OTP_HMAC_KEY, "pin-otp|" + userId + "|" + code)}, as hex. */
    String hash(String userId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("pin-otp|" + userId + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void audit(String actor, String userId, String action, String detail) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details) "
                + "VALUES (?, ?, 'PIN_OTP', ?, JSON_OBJECT('detail', ?))", actor, action, userId, detail);
    }

    private static String codeKey(String userId) {
        return "pin-otp:code:" + userId;
    }

    private static String attemptsKey(String userId) {
        return "pin-otp:attempts:" + userId;
    }

    private static String decidedKey(String userId) {
        return "pin-otp:decided:" + userId;
    }

    private static String cooldownKey(String userId) {
        return "pin-otp:cooldown:" + userId;
    }
}
