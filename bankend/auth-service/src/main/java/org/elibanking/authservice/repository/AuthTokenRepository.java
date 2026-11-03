package org.elibanking.authservice.repository;

import org.elibanking.authservice.entity.AuthToken;
import org.elibanking.authservice.entity.TokenType;
import org.elibanking.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    Optional<AuthToken> findByTokenHashAndType(String tokenHash, TokenType type);

    /** Invalidate every still-active token of a type for a user (e.g. all sessions after a password reset). */
    @Modifying
    @Query("update AuthToken t set t.usedAt = :now where t.user = :user and t.type = :type and t.usedAt is null")
    int revokeAll(User user, TokenType type, Instant now);
}
