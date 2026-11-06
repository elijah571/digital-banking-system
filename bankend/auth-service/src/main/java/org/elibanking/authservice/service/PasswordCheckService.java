package org.elibanking.authservice.service;

import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.exception.RateLimitedException;
import org.elibanking.authservice.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks a password for wallet-service (threat model T7 and D-P10).
 * <ul>
 *   <li><b>Counted before the compare.</b> Each call takes its place with an atomic INCR. A call past the limit is refused
 *       without a comparison, so parallel calls cannot all get past it (the lesson of the 3c.5 review).</li>
 *   <li><b>Same cost for every user.</b> A user that does not exist is compared against a dummy hash, so the answer's
 *       timing does not say whether the account exists.</li>
 *   <li><b>Fail closed.</b> If Redis is down, the exception propagates before any comparison.</li>
 *   <li><b>Audited.</b> Each verdict and each refusal writes one audit_log row. The password is never written anywhere.</li>
 * </ul>
 */
@Service
public class PasswordCheckService {

    static final int MAX_CALLS = 5;
    static final Duration WINDOW = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final String dummyHash;

    public PasswordCheckService(StringRedisTemplate redis, UserRepository users, PasswordEncoder passwordEncoder,
                                JdbcTemplate jdbc) {
        this.redis = redis;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public boolean verify(String userId, String password, String actor) {
        String key = "password-check:" + userId;
        Long calls = redis.opsForValue().increment(key);
        if (calls == null) {
            throw new IllegalStateException("Redis returned no count for the password check");
        }
        if (calls == 1) {
            redis.expire(key, WINDOW);
        }
        if (calls > MAX_CALLS) {
            audit(actor, userId, "PASSWORD_CHECK_LIMITED", null);
            throw new RateLimitedException(retryAfterSeconds(key));
        }

        Optional<User> user = users.findById(userId);
        // The comparison always runs, so an unknown user costs the same as a known one.
        boolean matches = passwordEncoder.matches(password, user.map(User::getPasswordHash).orElse(dummyHash));
        boolean valid = matches && user.isPresent();
        audit(actor, userId, valid ? "PASSWORD_VERIFIED" : "PASSWORD_REJECTED", null);
        return valid;
    }

    private long retryAfterSeconds(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 0 ? WINDOW.toSeconds() : ttl;
    }

    private void audit(String actor, String userId, String action, String detail) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details) "
                + "VALUES (?, ?, 'USER', ?, JSON_OBJECT('detail', ?))", actor, action, userId, detail);
    }
}
