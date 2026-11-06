package org.elibanking.walletservice.pin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a transfer PIN, counts the failures, and sets or changes the PIN (wallet-plan 3.5; threat model T1, T6, T7, D-P1,
 * D-P2, D-P3, D-P12).
 *
 * <p>Each operation runs in one transaction under the user's row lock. Outcomes are returned rather than thrown: an exception
 * would roll the transaction back, and the failure would not be counted. The HTTP answer is chosen after the commit.
 */
@Service
public class PinService {

    /** Five failures lock the PIN (D-P2). */
    static final int MAX_FAILURES = 5;
    /** The first lock lasts this long. Each later lock doubles, up to {@link #LONGEST_LOCK}. */
    static final Duration FIRST_LOCK = Duration.ofMinutes(30);
    static final Duration LONGEST_LOCK = Duration.ofHours(24);
    /** A PIN is four digits (D-P1). */
    static final Pattern FORMAT = Pattern.compile("^\\d{4}$");

    public enum Outcome { VALID, WRONG, LOCKED, NOT_SET }

    /** @param retryAfterSeconds how long a locked user must wait; 0 unless the outcome is LOCKED */
    public record Check(Outcome outcome, long retryAfterSeconds) {
    }

    private final PinCredentialRepository credentials;
    private final PinHasher hasher;
    private final JdbcTemplate jdbc;

    public PinService(PinCredentialRepository credentials, PinHasher hasher, JdbcTemplate jdbc) {
        this.credentials = credentials;
        this.hasher = hasher;
        this.jdbc = jdbc;
    }

    /**
     * Checks the PIN for one user. A locked PIN is refused without a comparison. A right PIN clears the failures and the
     * lock level. A wrong one adds a failure, and the fifth locks the PIN for {@link #lockFor(int)}.
     */
    @Transactional
    public Check check(String userId, String pin, String actor) {
        Optional<PinCredential> found = credentials.findForUpdate(userId);
        if (found.isEmpty() || found.get().getPinHash() == null) {
            audit(actor, userId, "PIN_NOT_SET", null);
            return new Check(Outcome.NOT_SET, 0);
        }
        PinCredential credential = found.get();
        LocalDateTime now = LocalDateTime.now();

        if (isLocked(credential, now)) {
            audit(actor, userId, "PIN_REFUSED_LOCKED", null);
            return new Check(Outcome.LOCKED, secondsBetween(now, credential.getLockedUntil()));
        }
        clearExpiredLock(credential);

        if (hasher.matches(userId, pin, credential.getPinHash())) {
            credential.setFailedCount(0);
            credential.setLockoutLevel(0);
            credentials.save(credential);
            audit(actor, userId, "PIN_VERIFIED", null);
            return new Check(Outcome.VALID, 0);
        }

        registerFailure(credential, now, actor, "PIN_WRONG");
        return new Check(Outcome.WRONG, 0);
    }

    /**
     * Counts a wrong password on a PIN change (D-P3), so the password endpoint is not a second, unlimited guessing path. While
     * the PIN is locked, the failure is audited but not counted again: the lock already stands.
     */
    @Transactional
    public void recordPasswordFailure(String userId, String actor) {
        Optional<PinCredential> found = credentials.findForUpdate(userId);
        if (found.isEmpty() || found.get().getPinHash() == null) {
            return;
        }
        PinCredential credential = found.get();
        LocalDateTime now = LocalDateTime.now();
        if (isLocked(credential, now)) {
            audit(actor, userId, "PIN_PASSWORD_WRONG_WHILE_LOCKED", null);
            return;
        }
        clearExpiredLock(credential);
        registerFailure(credential, now, actor, "PIN_PASSWORD_WRONG");
    }

    /**
     * Sets the PIN, or changes it. The caller has already checked the one-time code and the password, and hashed the new PIN
     * (threat model D-P5). A successful change clears the failures and any lock (D-P12): the owner has proved both the code
     * and the password. The new hash is written under the row lock.
     *
     * @return {@code true} if an existing PIN was changed, {@code false} if this was the first PIN
     */
    @Transactional
    public boolean setPin(String userId, String newPinHash, String actor) {
        Optional<PinCredential> found = credentials.findForUpdate(userId);
        boolean changed = found.isPresent() && found.get().getPinHash() != null;
        PinCredential credential = found.orElseGet(() -> new PinCredential(userId, null));
        boolean wasLocked = credential.getLockedUntil() != null;

        credential.setPinHash(newPinHash);
        credential.setFailedCount(0);
        credential.setLockoutLevel(0);
        credential.setLockedUntil(null);
        credentials.save(credential);

        String action = changed ? "PIN_CHANGED" : "PIN_SET";
        audit(actor, userId, action, wasLocked ? "lock cleared" : null);
        return changed;
    }

    /** The lock for the given level: 30 minutes, then 60, 120, and so on, never more than 24 hours. */
    static Duration lockFor(int level) {
        long minutes = FIRST_LOCK.toMinutes() << Math.min(level - 1, 16);
        return minutes >= LONGEST_LOCK.toMinutes() ? LONGEST_LOCK : Duration.ofMinutes(minutes);
    }

    private static boolean isLocked(PinCredential credential, LocalDateTime now) {
        return credential.getLockedUntil() != null && now.isBefore(credential.getLockedUntil());
    }

    /** The lock has run out: the next five failures count from zero. The lock level is kept until a right PIN. */
    private static void clearExpiredLock(PinCredential credential) {
        if (credential.getLockedUntil() != null) {
            credential.setLockedUntil(null);
            credential.setFailedCount(0);
        }
    }

    /** One more failure. The fifth locks the PIN for the next level. The count is kept at five while the lock stands. */
    private void registerFailure(PinCredential credential, LocalDateTime now, String actor, String action) {
        int failures = credential.getFailedCount() + 1;
        credential.setFailedCount(failures);
        if (failures >= MAX_FAILURES) {
            int level = credential.getLockoutLevel() + 1;
            credential.setLockoutLevel(level);
            credential.setLockedUntil(now.plus(lockFor(level)));
            credentials.save(credential);
            audit(actor, credential.getUserId(), "PIN_LOCKED", "level " + level);
        } else {
            credentials.save(credential);
            audit(actor, credential.getUserId(), action, "failure " + failures + " of " + MAX_FAILURES);
        }
    }

    private static long secondsBetween(LocalDateTime from, LocalDateTime to) {
        return Math.max(1, Duration.between(from, to).toSeconds());
    }

    /** One audit row, in the same transaction as the change it records. The PIN is never written. */
    private void audit(String actor, String userId, String action, String detail) {
        // A failed write throws, and the operation fails with it: a change must not happen without its audit row.
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details) "
                + "VALUES (?, ?, 'PIN_CREDENTIAL', ?, JSON_OBJECT('detail', ?))", actor, action, userId, detail);
    }
}
