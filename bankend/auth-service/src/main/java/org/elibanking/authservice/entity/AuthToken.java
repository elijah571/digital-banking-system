package org.elibanking.authservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One-time / long-lived opaque tokens (email verification, password reset, refresh).
 * Only the SHA-256 hash of the token is stored, so a database leak does not leak usable tokens.
 */
@Entity
@Table(name = "auth_tokens", indexes = @Index(name = "idx_auth_tokens_user_type", columnList = "user_id,type"))
@Getter
@Setter
@NoArgsConstructor
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TokenType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Set when the token is consumed or revoked; a token can only be used once. */
    private Instant usedAt;

    @CreationTimestamp
    private Instant createdAt;

    public boolean isUsable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }
}
