package org.elibanking.walletservice.enquiry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.elibanking.walletservice.security.Caller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** The name enquiry for a transfer or a withdrawal (wallet-plan 8.4). A user token is required for every /api route. */
@RestController
@RequestMapping("/api/v1/transfers")
public class NameEnquiryController {

    public record EnquiryRequest(@NotBlank @Size(max = 20) String bankCode,
                                 @NotBlank @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be 10 digits") String accountNumber) {
    }

    /** The reference to present for the transfer, the holder's name to show the user, and when the reference stops working. */
    public record EnquiryResponse(String nameEnquiryRef, String accountName, Instant expiresAt) {
    }

    private final NameEnquiryService enquiries;

    public NameEnquiryController(NameEnquiryService enquiries) {
        this.enquiries = enquiries;
    }

    @PostMapping("/name-enquiry")
    public EnquiryResponse enquire(@Valid @RequestBody EnquiryRequest body, Caller caller) {
        NameEnquiryService.Result result = enquiries.enquire(caller.userId(), body.bankCode(), body.accountNumber());
        return new EnquiryResponse(result.ref(), result.accountName(), result.expiresAt());
    }
}
