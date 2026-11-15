package org.elibanking.accountservice.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Crypto subledger API; account-service remains the only owner of customer asset balances. */
@RestController
@RequestMapping("/internal/v1/crypto")
public class CryptoLedgerController {
    public record AssetRegistration(@NotBlank @Size(max=100) String assetKey) {}
    private final CryptoLedgerService ledger;
    public CryptoLedgerController(CryptoLedgerService ledger) { this.ledger = ledger; }

    @PostMapping("/assets")
    public void registerAsset(@Valid @RequestBody AssetRegistration request) { ledger.registerAsset(request.assetKey()); }

    @PostMapping("/operations")
    public CryptoLedgerApi.OperationResult operation(@Valid @RequestBody CryptoLedgerApi.Operation request,
            @AuthenticationPrincipal Jwt caller,
            @RequestHeader(name="X-Request-Id", required=false) String requestId) {
        return ledger.apply(request, caller.getSubject(), requestId);
    }

    @GetMapping("/balances/{userId}")
    public List<CryptoLedgerApi.Balance> balances(@PathVariable String userId) { return ledger.balances(userId); }
}
