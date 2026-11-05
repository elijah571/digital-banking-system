package org.elibanking.walletservice.pin;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PinCredentialRepository extends JpaRepository<PinCredential, String> {

    /**
     * Locks the user's row until the transaction ends ({@code SELECT ... FOR UPDATE}). Every PIN check for one user runs one
     * after another, so a parallel burst cannot read the same failure count twice (threat model T1 and T11).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PinCredential p where p.userId = :userId")
    Optional<PinCredential> findForUpdate(@Param("userId") String userId);
}
