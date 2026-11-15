package org.elibanking.accountservice.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigInteger;

public final class CryptoLedgerApi {
    private CryptoLedgerApi() {}

    public record Operation(
            @NotBlank @Pattern(regexp = "DEPOSIT_CREDIT|WITHDRAWAL_RESERVE|WITHDRAWAL_SETTLE|WITHDRAWAL_RELEASE") String operation,
            @NotBlank @Size(max = 255) String userId,
            @NotBlank @Size(max = 100) String assetKey,
            @NotNull BigInteger amountBaseUnits,
            @Size(max = 100) String feeAssetKey,
            BigInteger feeBaseUnits,
            @NotBlank @Size(max = 150) String idempotencyKey,
            @Size(max = 255) String reference) {}

    public record Balance(String assetKey, String availableBaseUnits, String reservedBaseUnits) {}
    public record OperationResult(long entryId, boolean replayed) {}
}
