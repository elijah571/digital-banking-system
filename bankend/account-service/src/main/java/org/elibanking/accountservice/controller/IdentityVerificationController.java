package org.elibanking.accountservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.elibanking.accountservice.security.Caller;
import org.elibanking.accountservice.verification.AccountUpgradeRequest;
import org.elibanking.accountservice.verification.DocumentIdentityRequest;
import org.elibanking.accountservice.verification.IdentityVerificationService;
import org.elibanking.accountservice.verification.VerificationStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts/verification")
@RequiredArgsConstructor
@Tag(name = "Identity verification", description = "Customer-submitted evidence and manual administrator review")
public class IdentityVerificationController {

    private final IdentityVerificationService verificationService;

    @GetMapping
    @Operation(summary = "Get identity verification and account upgrade status")
    public VerificationStatusResponse status(Caller caller) {
        return verificationService.status(caller);
    }

    @PostMapping("/identity")
    @Operation(summary = "Verify an identity document and selfie")
    public VerificationStatusResponse verifyIdentity(
            @Valid @RequestBody DocumentIdentityRequest request, Caller caller) {
        return verificationService.verifyIdentity(caller, request);
    }

    @PostMapping("/upgrade")
    @Operation(summary = "Upgrade owned accounts using BVN, address, and facial verification")
    public VerificationStatusResponse upgradeAccount(
            @Valid @RequestBody AccountUpgradeRequest request, Caller caller) {
        return verificationService.upgradeAccount(caller, request);
    }

    @GetMapping("/admin/reviews")
    public List<Map<String, Object>> pendingReviews(Caller caller) {
        return verificationService.pendingReviews(caller);
    }

    @PostMapping("/admin/reviews/{ownerId}")
    public VerificationStatusResponse review(@PathVariable String ownerId, @RequestParam String type,
            @RequestParam boolean approve, @RequestParam(required = false) String reason, Caller caller) {
        return verificationService.review(caller, ownerId, type, approve, reason);
    }
}
