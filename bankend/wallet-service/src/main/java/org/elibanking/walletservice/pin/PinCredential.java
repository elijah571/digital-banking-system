package org.elibanking.walletservice.pin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One user's PIN and its lockout state (wallet-plan 3.5, threat model D-P2). The hash is never the PIN. {@code pinHash} is
 * null until the user sets a PIN.
 */
@Entity
@Table(name = "pin_credential")
@Getter
@Setter
@NoArgsConstructor
public class PinCredential {

    @Id
    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "pepper_version", nullable = false)
    private int pepperVersion = 1;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "lockout_level", nullable = false)
    private int lockoutLevel;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PinCredential(String userId, String pinHash) {
        this.userId = userId;
        this.pinHash = pinHash;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
