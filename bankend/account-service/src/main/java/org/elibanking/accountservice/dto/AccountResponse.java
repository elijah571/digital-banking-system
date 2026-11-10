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
import org.elibanking.accountservice.entity.AccountTier;
import org.elibanking.accountservice.entity.AccountType;
import tools.jackson.databind.annotation.JsonSerialize;

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
    private AccountTier accountTier;
    // Money in responses is a string such as "9000.00" (wallet-plan D3). Requests still take a number or a string.
    @JsonSerialize(using = MoneyJson.Serializer.class)
    private  BigDecimal balance;
    @JsonSerialize(using = MoneyJson.Serializer.class)
    private BigDecimal initialDeposit;
    @JsonSerialize(using = MoneyJson.Serializer.class)
    private BigDecimal dailyTransactionLimit;
    private LocalDateTime createdAt;

}
