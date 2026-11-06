package org.elibanking.walletservice.pin;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.client.AuthServiceClient;
import org.elibanking.walletservice.client.AuthServiceClient.PasswordCheck;
import org.elibanking.walletservice.client.AuthServiceClient.PasswordCheckRequest;
import org.elibanking.walletservice.exception.PasswordCheckUnavailableException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sets or changes the transfer PIN (wallet-plan 3.5; threat model T2, T5, T7, D-P3, D-P5, D-P6, D-P12).
 *
 * <p>The steps run in a fixed order, and each one can stop the change:
 * <ol>
 *   <li>the one-time code: five tries, single use. Only a right code goes on;</li>
 *   <li>the current password, checked by auth-service. A wrong one counts toward the PIN lockout (D-P3). An auth-service
 *       rate limit is passed on as 429, and an unreachable auth-service is 503 (fail closed);</li>
 *   <li>the new PIN, hashed, then written under the row lock. A success clears any lock (D-P12);</li>
 *   <li>an email to the owner that the PIN changed (D-P6). It is sent after the commit. If it cannot be sent, the change still
 *       stands, and the failure is logged.</li>
 * </ol>
 * A code that is used up in step 2 is spent, so the user asks for another. That is the single-use rule working as intended.
 */
@Slf4j
@Service
public class PinChangeService {

    static final String CHANGED_TOPIC = "wallet.pin.changed";
    /** The window auth-service applies to password checks (ea8e2a4); a 429 without a header is answered with it. */
    static final long PASSWORD_WINDOW_SECONDS = 30 * 60;

    public enum Result { SET, CHANGED, WRONG_CODE, NO_CODE, CODE_USED, WRONG_PASSWORD, PASSWORD_RATE_LIMITED }

    /** @param codeTriesLeft tries left after a WRONG_CODE; @param retryAfterSeconds for PASSWORD_RATE_LIMITED */
    public record Outcome(Result result, int codeTriesLeft, long retryAfterSeconds) {
    }

    private final PinOtpService codes;
    private final AuthServiceClient auth;
    private final PinService pins;
    private final PinHasher hasher;
    private final KafkaTemplate<String, Object> kafka;

    public PinChangeService(PinOtpService codes, AuthServiceClient auth, PinService pins, PinHasher hasher,
                            KafkaTemplate<String, Object> kafka) {
        this.codes = codes;
        this.auth = auth;
        this.pins = pins;
        this.hasher = hasher;
        this.kafka = kafka;
    }

    public Outcome change(String userId, String email, String code, String password, String newPin, String actor) {
        PinOtpService.Attempt attempt = codes.consume(userId, code, actor);
        switch (attempt.result()) {
            case WRONG -> {
                return new Outcome(Result.WRONG_CODE, attempt.attemptsLeft(), 0);
            }
            case NONE -> {
                return new Outcome(Result.NO_CODE, 0, 0);
            }
            case REFUSED -> {
                return new Outcome(Result.CODE_USED, 0, 0);
            }
            case VALID -> {
                // The code is right and spent; the password is next.
            }
        }

        PasswordCheck check = checkPassword(userId, password);
        if (check == null) {
            return new Outcome(Result.PASSWORD_RATE_LIMITED, 0, PASSWORD_WINDOW_SECONDS);
        }
        if (!check.valid()) {
            pins.recordPasswordFailure(userId, actor);
            return new Outcome(Result.WRONG_PASSWORD, 0, 0);
        }

        // Argon2 is slow, so the hash is made before the row lock is taken.
        boolean changed = pins.setPin(userId, hasher.hash(userId, newPin), actor);
        notifyChanged(userId, email);
        return new Outcome(changed ? Result.CHANGED : Result.SET, 0, 0);
    }

    /** The password answer, or {@code null} when auth-service has rate-limited the user. Any other failure is 503. */
    private PasswordCheck checkPassword(String userId, String password) {
        try {
            return auth.verifyPassword(new PasswordCheckRequest(userId, password));
        } catch (FeignException e) {
            if (e.status() == 429) {
                return null;
            }
            log.warn("Password check failed with status {} for user {}", e.status(), userId);
            throw new PasswordCheckUnavailableException();
        } catch (RuntimeException e) {
            log.warn("Password check did not answer for user {}: {}", userId, e.getClass().getSimpleName());
            throw new PasswordCheckUnavailableException();
        }
    }

    private void notifyChanged(String userId, String email) {
        try {
            kafka.send(CHANGED_TOPIC, userId, Map.of("userId", userId, "email", email)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The PIN has already changed. A missing email is logged, and the change stands.
            log.warn("PIN change notice not sent for user {}", userId);
        }
    }
}
