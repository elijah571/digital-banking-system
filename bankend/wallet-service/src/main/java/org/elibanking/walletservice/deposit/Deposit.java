package org.elibanking.walletservice.deposit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One card deposit into a wallet (wallet-plan 3.5). The id is also the Stripe metadata depositId and the ledger key stem. */
@Entity
@Table(name = "deposit")
@Getter
@Setter
@NoArgsConstructor
public class Deposit {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositStatus status;

    @Column(name = "payment_intent_id", length = 100)
    private String paymentIntentId;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Deposit pending(String id, String userId, String accountNumber, BigDecimal amount, String currency,
                                  Instant expiresAt) {
        Deposit deposit = new Deposit();
        deposit.setId(id);
        deposit.setUserId(userId);
        deposit.setAccountNumber(accountNumber);
        deposit.setAmount(amount);
        deposit.setCurrency(currency);
        deposit.setStatus(DepositStatus.PENDING);
        deposit.setExpiresAt(expiresAt);
        deposit.setCreatedAt(Instant.now());
        return deposit;
    }
}
