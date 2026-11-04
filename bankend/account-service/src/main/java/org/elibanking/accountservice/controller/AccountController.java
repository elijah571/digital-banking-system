package org.elibanking.accountservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.accountservice.dto.AccountResponse;
import org.elibanking.accountservice.dto.CreateAccountRequest;
import org.elibanking.accountservice.security.Caller;
import org.elibanking.accountservice.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Open accounts, read balances, and the balance operations used by the transfer saga")
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/create")
    @Operation(summary = "Open a new account",
            description = "Creates an ACTIVE account owned by the logged-in user, with a generated 10-digit account number. "
                    + "The account email is the user's login email. The initial deposit becomes the opening balance. "
                    + "Daily limit: SAVINGS 100,000 · CURRENT 5,000,000.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed (missing name, invalid email, non-positive deposit…)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "An account already exists for this email",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request, Caller caller
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request, caller));
    }

    @GetMapping("/me")
    @Operation(summary = "My accounts", description = "Accounts owned by the logged-in user.")
    @ApiResponse(responseCode = "200", description = "List of accounts (may be empty)")
    public List<AccountResponse> getMyAccounts(Caller caller) {
        return accountService.getMyAccounts(caller);
    }

    @GetMapping
    @Operation(summary = "List all accounts (ADMIN)", description = "Every account in the bank, newest first.")
    @ApiResponse(responseCode = "200", description = "All accounts")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAllAccounts();
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Get account details", description = "Users can only read their own accounts; admins can read any.")
    @ApiResponse(responseCode = "200", description = "Account found")
    @ApiResponse(responseCode = "403", description = "Account belongs to another user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AccountResponse> getAccount(
            @Parameter(description = "10-digit account number", example = "3693612260") @PathVariable String accountNumber,
            Caller caller
    ) {
        return ResponseEntity.ok(accountService.getAccount(accountNumber, caller));
    }

    @GetMapping("/{accountNumber}/balance")
    @Operation(summary = "Get account balance", description = "Returns the current balance as a plain number, e.g. `9000.00`.")
    @ApiResponse(responseCode = "200", description = "Current balance")
    @ApiResponse(responseCode = "403", description = "Account belongs to another user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BigDecimal> getBalance(
            @Parameter(description = "10-digit account number", example = "3693612260") @PathVariable String accountNumber,
            Caller caller
    ) {
        return ResponseEntity.ok(accountService.getBalance(accountNumber, caller));
    }

    @PutMapping("/{accountNumber}/block")
    @Operation(summary = "Block an account (ADMIN)",
            description = "Sets the account status to BLOCKED. A blocked account can no longer send money. "
                    + "Also triggered automatically when a wrong OTP is entered.")
    @ApiResponse(responseCode = "200", description = "Account blocked")
    @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<String> blockAccount(
            @Parameter(description = "10-digit account number", example = "3693612260") @PathVariable String accountNumber
    ) {
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("Account blocked successfully");
    }
}
