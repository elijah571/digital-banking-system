package org.elibanking.walletservice.pin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Stores a PIN as {@code Argon2id(HMAC-SHA256(PIN_PEPPER, userId | pin))} (wallet-plan 3.5, threat model T3 and D-P7).
 * <ul>
 *   <li>The pepper is kept outside the database, so a stolen table is not enough to test PINs offline.</li>
 *   <li>The user id is part of the keyed input, so two users with the same PIN get different hashes.</li>
 *   <li>Argon2id is slow on purpose: each guess costs real time.</li>
 * </ul>
 */
@Component
public class PinHasher {

    /** OWASP's Argon2id minimum: 19 MiB of memory, 2 passes, 1 lane. The salt is 16 bytes and the hash 32. */
    private final Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final byte[] pepper;

    public PinHasher(@Value("${pin.pepper}") String pepper) {
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String userId, String pin) {
        return argon2.encode(keyed(userId, pin));
    }

    public boolean matches(String userId, String pin, String encoded) {
        return argon2.matches(keyed(userId, pin), encoded);
    }

    /** {@code HMAC-SHA256(pepper, userId | pin)} as lowercase hex. User ids never contain '|'. */
    String keyed(String userId, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + "|" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
