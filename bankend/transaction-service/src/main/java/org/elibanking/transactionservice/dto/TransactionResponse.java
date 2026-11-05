package org.elibanking.transactionservice.dto;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.hibernate.annotations.CreationTimestamp;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionResponse {
    private String id;
    private String senderAccountNumber;
    private String receiverAccountNumber;
    private String description;
    private String failureReason;
    private String referenceNumber;
    private TransactionType type;
    private TransactionStatus status;
    /** A string such as "250.00" in responses (wallet-plan D3). Requests still take a number or a string. */
    @JsonSerialize(using = MoneyJson.Serializer.class)
    private BigDecimal amount;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;


    }
