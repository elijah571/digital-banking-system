package org.elibanking.accountservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.accountservice.dto.AccountResponse;
import org.elibanking.accountservice.dto.CreateAccountRequest;
import org.elibanking.accountservice.entity.Account;
import org.elibanking.accountservice.exception.AccountAccessDeniedException;
import org.elibanking.accountservice.exception.AccountAlreadyExistsException;
import org.elibanking.accountservice.exception.AccountNotFoundException;
import org.elibanking.accountservice.exception.AccountOperationException;
import org.elibanking.accountservice.exception.InvalidOpeningDepositException;
import org.elibanking.accountservice.ledger.LedgerPostingService;
import org.elibanking.accountservice.entity.AccountStatus;
import org.elibanking.accountservice.entity.AccountType;
import org.elibanking.accountservice.repository.AccountRepository;
import org.elibanking.accountservice.security.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerPostingService ledger;
    private static final SecureRandom secureRandom = new SecureRandom();

    /** Opening deposits (wallet-plan 3.4, D4): on in dev, off in prod, where accounts open at 0 and are funded by a deposit. */
    @Value("${account.opening-deposit.enabled:false}")
    private boolean openingDepositEnabled;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, Caller caller) {
        // Logged-in users always open the account under their own (verified) email.
        String email = caller.isInternal() ? request.getEmail() : caller.email();
        if (email == null || email.isBlank()) {
            throw new AccountOperationException("Email is required");
        }
        log.info("Creating account for: {}", email);
        if (accountRepository.existsByEmail(email)) {
            throw new AccountAlreadyExistsException("Account already exists for this email: " + email);
        }

        BigDecimal deposit = request.getInitialDeposit() == null ? BigDecimal.ZERO : request.getInitialDeposit();
        if (deposit.signum() < 0 || deposit.stripTrailingZeros().scale() > 2) {
            throw new InvalidOpeningDepositException("The opening deposit must be zero or positive, with at most 2 decimals");
        }
        if (deposit.signum() > 0 && !openingDepositEnabled) {
            throw new InvalidOpeningDepositException(
                    "An opening deposit is not accepted: accounts open at 0 and are funded by a deposit");
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setAccountNumber(generateAccountNumber());
        account.setEmail(email);
        account.setOwnerId(caller.userId());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVINGS
                        ? new BigDecimal("100000")
                        : new BigDecimal("5000000")
        );

        // Flushed first, so the wallet can link to the row. The ledger sets the balance: the opening entry, if there is a
        // deposit, moves it into the wallet, and accounts.balance mirrors the wallet in the same transaction.
        Account savedAccount = accountRepository.saveAndFlush(account);
        ledger.openAccount(savedAccount.getId(), "NGN", deposit);
        savedAccount.setBalance(deposit.setScale(2, RoundingMode.UNNECESSARY));
        log.info("Account created: {}", savedAccount.getAccountNumber());
        return mapToResponse(savedAccount);
    }

    public AccountResponse getAccount(String accountNumber, Caller caller) {
        return mapToResponse(findAccessible(accountNumber, caller));
    }

    public BigDecimal getBalance(String accountNumber, Caller caller) {
        return findAccessible(accountNumber, caller).getBalance();
    }

    /** Accounts owned by the logged-in user. */
    public List<AccountResponse> getMyAccounts(Caller caller) {
        return accountRepository.findAllByOwnerIdOrderByCreatedAtDesc(caller.userId()).stream()
                .map(this::mapToResponse)
                .toList();
    }

    /** Every account (ADMIN). */
    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapToResponse)
                .toList();
    }

    private Account findAccessible(String accountNumber, Caller caller) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
        if (!caller.canAccess(account.getOwnerId())) {
            throw new AccountAccessDeniedException("You do not have access to account " + accountNumber);
        }
        return account;
    }

    /**
     * Block account called by Fraud detection service via Kafka
     */
    @Transactional
    public void blockAccount(String accountNumber) {
        log.info("Blocking account: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
        account.setAccountStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account blocked successfully: {}", accountNumber);
    }

    private String generateAccountNumber() {
        String accountNumber;
        do {
            long number = 1_000_000_000L + secureRandom.nextLong(9_000_000_000L);
            accountNumber = String.format("%010d", number);
        } while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private AccountResponse mapToResponse(Account account) {
        AccountResponse response = new AccountResponse();
        response.setId(account.getId());
        response.setOwnerId(account.getOwnerId());
        response.setAccountHolderName(account.getAccountHolderName());
        response.setAccountNumber(account.getAccountNumber());
        response.setEmail(account.getEmail());
        response.setPhone(account.getPhone());
        response.setAccountType(account.getAccountType());
        response.setStatus(account.getAccountStatus());
        response.setAccountTier(account.getAccountTier());
        response.setBalance(account.getBalance());
        response.setDailyTransactionLimit(account.getDailyTransactionLimit());
        response.setCreatedAt(account.getCreatedAt());
        return response;
    }
}
