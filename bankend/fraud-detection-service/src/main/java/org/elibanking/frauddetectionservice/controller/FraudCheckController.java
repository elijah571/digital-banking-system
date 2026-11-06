package org.elibanking.frauddetectionservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.elibanking.frauddetectionservice.model.FraudCheckResult;
import org.elibanking.frauddetectionservice.service.FraudDetectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Set;

/** The synchronous fraud check for an outbound payout (wallet-plan 3.5, step 3). Only wallet-service may call it, see SecurityConfig. */
@RestController
@RequestMapping("/internal/v1/fraud")
public class FraudCheckController {

    private static final Set<String> TYPES = Set.of("WITHDRAWAL", "EXTERNAL_TRANSFER");

    /** The type is carried so the rules can differ by kind later. Today both kinds use the same rules. */
    public record CheckRequest(@NotBlank String type,
                               @NotBlank @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be 10 digits") String accountNumber,
                               @NotBlank @Pattern(regexp = "^\\d+(\\.\\d+)?$", message = "amount must be a number") String amount) {
    }

    private final FraudDetectionService fraud;

    public FraudCheckController(FraudDetectionService fraud) {
        this.fraud = fraud;
    }

    @PostMapping("/check")
    public FraudCheckResult check(@Valid @RequestBody CheckRequest body) {
        if (!TYPES.contains(body.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be WITHDRAWAL or EXTERNAL_TRANSFER");
        }
        return fraud.checkWithdrawal(body.accountNumber(), new BigDecimal(body.amount()));
    }
}
