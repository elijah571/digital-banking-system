package org.elibanking.transactionservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String senderAccountNumber;

    /** auth-service user id of the user who started the transfer. */
    @Column(name = "sender_user_id")
    private String senderUserId;

    @Column(nullable = false)
    private String receiverAccountNumber;

    private String description;

    private String failureReason;

    @Column(nullable = false)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    private LocalDateTime createdAt;


    private LocalDateTime completedAt;

    /** When the amount was held in TRANSFER_IN_FLIGHT (wallet-plan 3.4). Null until then; the recovery job holds those transfers. */
    private LocalDateTime heldAt;

    /**
     * Optimistic lock (wallet-plan section 4, slice 3c.1). Two writers that read the same version cannot both save, which
     * fixes the verify race: a second verify of the same transfer fails instead of completing it twice.
     */
    @Version
    private Long version;
}
