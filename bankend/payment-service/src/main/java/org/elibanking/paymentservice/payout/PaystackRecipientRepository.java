package org.elibanking.paymentservice.payout;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.Optional;

/** Stores Paystack recipient codes by a keyed account-pair fingerprint, without persisting bank account numbers. */
@Repository
public class PaystackRecipientRepository {

    private final JdbcTemplate jdbc;
    private final byte[] fingerprintKey;

    public PaystackRecipientRepository(JdbcTemplate jdbc, @Value("${internal-auth.jwt-secret}") String fingerprintKey) {
        this.jdbc = jdbc;
        this.fingerprintKey = fingerprintKey.getBytes(StandardCharsets.UTF_8);
    }

    public Optional<String> find(String bankCode, String accountNumber) {
        return jdbc.query("SELECT recipient_code FROM paystack_recipient WHERE account_fingerprint = ?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), fingerprint(bankCode, accountNumber));
    }

    public String saveIfAbsent(String bankCode, String accountNumber, String recipientCode) {
        String fingerprint = fingerprint(bankCode, accountNumber);
        try {
            jdbc.update("INSERT INTO paystack_recipient (account_fingerprint, recipient_code) VALUES (?, ?)",
                    fingerprint, recipientCode);
            return recipientCode;
        } catch (DuplicateKeyException concurrentCreate) {
            return jdbc.queryForObject("SELECT recipient_code FROM paystack_recipient WHERE account_fingerprint = ?",
                    String.class, fingerprint);
        }
    }

    private String fingerprint(String bankCode, String accountNumber) {
        try {
            Mac digest = Mac.getInstance("HmacSHA256");
            digest.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            byte[] bytes = digest.doFinal((bankCode + ":" + accountNumber).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception unavailable) {
            throw new IllegalStateException("recipient fingerprint could not be created", unavailable);
        }
    }
}
