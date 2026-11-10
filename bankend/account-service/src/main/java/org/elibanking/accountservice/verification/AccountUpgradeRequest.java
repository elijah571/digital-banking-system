package org.elibanking.accountservice.verification;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountUpgradeRequest(
        @NotBlank @Pattern(regexp = "\\d{11}", message = "BVN must contain exactly 11 digits") String bvn,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 120) String lastName,
        @NotBlank @Pattern(regexp = "UTILITY_BILL|BANK_STATEMENT|LEASE_AGREEMENT|GOVERNMENT_LETTER")
        String addressDocumentType,
        @NotBlank @Size(min = 8, max = 500) String residentialAddress,
        @NotBlank @Size(max = 7_000_000) String addressDocumentImage,
        @NotBlank @Size(max = 7_000_000) String selfieImage,
        @AssertTrue(message = "Consent is required before BVN verification") boolean consentGranted
) {
}
