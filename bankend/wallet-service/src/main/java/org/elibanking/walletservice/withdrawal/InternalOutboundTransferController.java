package org.elibanking.walletservice.withdrawal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.elibanking.walletservice.enquiry.NameEnquiryService;
import org.elibanking.walletservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal transaction-service calls used to validate and open external transfers. */
@RestController
@RequestMapping("/internal/v1")
public class InternalOutboundTransferController {

    public record NameValidationRequest(@NotBlank String userId, @NotBlank @Size(max = 64) String nameEnquiryRef,
                                        @NotBlank @Size(max = 20) String bankCode,
                                        @NotBlank @Size(min = 10, max = 10) String accountNumber) {
    }

    public record NameValidationResponse(String accountName) {
    }

    public record OutboundTransferRequest(@NotBlank String userId, @NotBlank @Size(max = 200) String accountName,
                                         @NotBlank @Pattern(regexp = "^\\d{10}$") String accountNumber,
                                         @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$") String amount,
                                         @NotBlank @Size(max = 20) String bankCode,
                                         @NotBlank @Pattern(regexp = "^\\d{10}$") String destinationAccountNumber,
                                         @NotBlank @Size(max = 64) String nameEnquiryRef,
                                         @Size(max = 255) String narration, @NotBlank @Pattern(regexp = "^\\d{4}$") String pin) {
    }

    private final NameEnquiryService enquiries;
    private final WithdrawalService withdrawals;

    public InternalOutboundTransferController(NameEnquiryService enquiries, WithdrawalService withdrawals) {
        this.enquiries = enquiries;
        this.withdrawals = withdrawals;
    }

    @PostMapping("/name-enquiries/validate")
    public NameValidationResponse validate(@Valid @RequestBody NameValidationRequest request) {
        return new NameValidationResponse(enquiries.validate(request.userId(), request.nameEnquiryRef(),
                request.bankCode(), request.accountNumber()).getAccountName());
    }

    @PostMapping(value = "/outbound-transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> transfer(@RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody OutboundTransferRequest request) {
        if (key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 255 characters");
        }
        WithdrawalRequest payout = new WithdrawalRequest(request.accountNumber(), request.amount(), request.bankCode(),
                request.destinationAccountNumber(), request.nameEnquiryRef(), request.narration(), request.pin());
        WithdrawalService.Reply reply = withdrawals.externalTransfer(
                new Caller(request.userId(), null, null, "USER"), key, payout, request.accountName());
        return ResponseEntity.status(HttpStatus.valueOf(reply.status())).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }
}
