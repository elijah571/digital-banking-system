package org.elibanking.walletservice.payout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One outbound payout: a withdrawal or an external transfer (wallet-plan 8.3). Its ledger key is {@code out-{id}}. */
@Entity
@Table(name = "outbound_payout")
@Getter
@Setter
@NoArgsConstructor
public class OutboundPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PayoutType type;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "destination_account_number", nullable = false, length = 20)
    private String destinationAccountNumber;

    @Column(name = "destination_account_name", length = 200)
    private String destinationAccountName;

    @Column(name = "narration", length = 255)
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private PayoutState state;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The ledger and provider key for this payout, {@code out-{id}} (8.3). Only meaningful once the row has an id. */
    public String ledgerKey() {
        return "out-" + id;
    }
}
