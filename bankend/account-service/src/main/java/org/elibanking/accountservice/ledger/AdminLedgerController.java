package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.security.Caller;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN only (the security chain checks {@code /api/v1/admin/**}). Reconciliation and the admin credit and deduct (wallet-plan
 * 3.2, 3.4, D5). Amounts are strings.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminLedgerController {

    public record ReconciliationView(boolean clean, List<String> issues) {
    }

    private final ReconciliationService reconciliation;
    private final AdminAdjustments adjustments;

    public AdminLedgerController(ReconciliationService reconciliation, AdminAdjustments adjustments) {
        this.reconciliation = reconciliation;
        this.adjustments = adjustments;
    }

    @GetMapping("/ledger/reconciliation")
    public ReconciliationView reconciliation() {
        ReconciliationReport report = reconciliation.check();
        return new ReconciliationView(report.clean(), report.issues());
    }

    @PutMapping("/accounts/{accountNumber}/credit")
    public ResponseEntity<String> credit(@PathVariable String accountNumber, @RequestParam String amount,
                                         @RequestParam String reason,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey, Caller caller) {
        return adjust(AdminAdjustments.Direction.CREDIT, accountNumber, amount, reason, idempotencyKey, caller);
    }

    @PutMapping("/accounts/{accountNumber}/deduct")
    public ResponseEntity<String> deduct(@PathVariable String accountNumber, @RequestParam String amount,
                                         @RequestParam String reason,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey, Caller caller) {
        return adjust(AdminAdjustments.Direction.DEDUCT, accountNumber, amount, reason, idempotencyKey, caller);
    }

    private ResponseEntity<String> adjust(AdminAdjustments.Direction direction, String accountNumber, String amount,
                                          String reason, String idempotencyKey, Caller caller) {
        AdminAdjustments.Result result = adjustments.adjust(accountNumber, direction, amount, reason, idempotencyKey,
                caller.userId());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.bodyJson());
    }
}
