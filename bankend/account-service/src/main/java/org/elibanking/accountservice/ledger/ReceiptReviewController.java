package org.elibanking.accountservice.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Internal receipt quarantine API; the wallet service records provider receipts it cannot credit. */
@RestController
@RequestMapping("/internal/v1/ledger/receipts")
public class ReceiptReviewController {

    private final ReceiptReview receipts;

    public ReceiptReviewController(ReceiptReview receipts) {
        this.receipts = receipts;
    }

    @PostMapping("/quarantine")
    public ResponseEntity<QuarantineResponse> quarantine(@Valid @RequestBody QuarantineRequest request) {
        boolean recorded = receipts.quarantine(request.source(), request.providerReference(), request.depositId(),
                request.reason(), request.currency(), request.amount(), request.payload().toString());
        return ResponseEntity.status(recorded ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new QuarantineResponse(recorded));
    }

    public record QuarantineRequest(
            @NotBlank String source,
            @NotBlank String providerReference,
            String depositId,
            @NotBlank String reason,
            @NotBlank String currency,
            @NotBlank String amount,
            @NotNull JsonNode payload) {
    }

    public record QuarantineResponse(boolean recorded) {
    }
}
