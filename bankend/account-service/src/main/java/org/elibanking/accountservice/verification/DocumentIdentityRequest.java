package org.elibanking.accountservice.verification;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DocumentIdentityRequest(
        @NotBlank
        @Pattern(regexp = "PASSPORT|DRIVING_LICENSE|NIN_SLIP|VOTER_CARD")
        String documentType,
        @NotBlank @Size(max = 7_000_000) String documentImage,
        @NotBlank @Size(max = 7_000_000) String selfieImage,
        @AssertTrue(message = "Consent is required before identity verification") boolean consentGranted
) {
}
