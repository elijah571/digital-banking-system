
package org.elibanking.transactionservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.elibanking.transactionservice.entity.TransactionType;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionRequest {

    @Schema(example = "3693612260")
    @NotBlank(message = "Sender account number required")
    private String senderAccountNumber;

    @Schema(example = "3583221418")
    @NotBlank(message = "Receiver account number required")
    private String receiverAccountNumber;

    @Schema(example = "1000")
    @NotNull(message = "Amount is required")
    @Positive(message = "amount must be positive")
    private BigDecimal amount;

    @Schema(example = "Rent for November")
    private String description;

    @Schema(example = "TRANSFER")
    @NotNull(message = "Transaction type is required")
    private TransactionType type;

    /**
     * The user's transfer PIN. Required while TRANSFER_PIN_REQUIRED is on (wallet-plan 8.22). Never logged.
     */
    @Schema(description = "Transfer PIN. Required while TRANSFER_PIN_REQUIRED is true.", example = "1234")
    @Size(max = 10)
    private String pin;

    @Schema(description = "Destination bank code; omit for an account at our bank.", example = "058")
    @Size(max = 20)
    private String bankCode;

    @Schema(description = "Recent name-enquiry reference, required for another bank and optional for our bank.")
    @Size(max = 64)
    private String nameEnquiryRef;
}
