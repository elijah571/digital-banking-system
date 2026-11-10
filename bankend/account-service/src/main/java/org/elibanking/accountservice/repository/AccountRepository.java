package org.elibanking.accountservice.repository;

import org.elibanking.accountservice.entity.Account;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    boolean existsByEmail(String email);

    boolean existsByAccountNumber(String accountNumber);


    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<Account> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query(value = """
            UPDATE accounts
            SET account_tier = 'VERIFIED',
                daily_transaction_limit = GREATEST(daily_transaction_limit, :dailyLimit),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE owner_id = :ownerId
            """, nativeQuery = true)
    int upgradeOwnedAccounts(@Param("ownerId") String ownerId, @Param("dailyLimit") BigDecimal dailyLimit);
}
