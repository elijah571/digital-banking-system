package org.elibanking.accountservice.dto;

import jakarta.persistence.Column;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.elibanking.accountservice.entity.AccountStatus;
import org.elibanking.accountservice.entity.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {
    private String id;
    private String ownerId;
    private  String accountNumber;
    private  String accountHolderName;
    private  String email;
    private  String phone;
    private AccountType accountType;
    private AccountStatus status;
    private  BigDecimal balance;
    private BigDecimal initialDeposit;
    private BigDecimal dailyTransactionLimit;
    private LocalDateTime createdAt;

}
