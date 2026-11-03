package org.elibanking.accountservice.repository;

import org.elibanking.accountservice.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    boolean existsByEmail(String email);

    boolean existsByAccountNumber(String accountNumber);


    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<Account> findAllByOrderByCreatedAtDesc();
}
