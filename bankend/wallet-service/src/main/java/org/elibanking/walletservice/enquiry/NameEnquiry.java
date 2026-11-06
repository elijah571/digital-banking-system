package org.elibanking.walletservice.enquiry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One name enquiry, held under its reference until it expires (wallet-plan 8.4). */
@Entity
@Table(name = "name_enquiry")
@Getter
@Setter
@NoArgsConstructor
public class NameEnquiry {

    @Id
    @Column(name = "ref", length = 64)
    private String ref;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "account_name", nullable = false, length = 200)
    private String accountName;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
