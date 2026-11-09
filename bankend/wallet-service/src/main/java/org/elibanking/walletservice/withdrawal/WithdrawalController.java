package org.elibanking.walletservice.withdrawal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Withdrawals to another bank (wallet-plan 8.3). A user token is required for every /api route. */
@RestController
@RequestMapping("/api/v1/wallet")
public class WithdrawalController {

    private final WithdrawalService withdrawals;

    public WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    /** 202 with the REQUESTED payout once its money is held. A repeat with the same key gets the same answer. */
    @PostMapping(value = "/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WithdrawalRequest body,
            Caller caller) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new RequestRefusedException(HttpStatus.BAD_REQUEST,
                    "An Idempotency-Key header of 1 to 255 characters is required");
        }
        WithdrawalService.Reply reply = withdrawals.request(caller, key, body);
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    /** One of the caller's withdrawals, with its status. Another user's withdrawal is 404. */
    @GetMapping(value = "/withdrawals/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WithdrawalResponse get(@PathVariable long id, Caller caller) {
        return withdrawals.view(caller, id);
    }

    @GetMapping(value = "/payouts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WithdrawalResponse getPayout(@PathVariable long id, Caller caller) {
        return withdrawals.viewPayout(caller, id);
    }

    /** The reason an admin gives for rejecting a withdrawal. */
    public record Decision(@NotBlank @Size(max = 255) String reason) {
    }

    /** An admin approves a withdrawal that is waiting. The requester cannot approve their own (wallet-plan 8.3). */
    @PostMapping(value = {"/withdrawals/{id}/approve", "/payouts/{id}/approve"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public WithdrawalResponse approve(@PathVariable long id, Caller caller) {
        return withdrawals.approve(caller, id);
    }

    /** An admin rejects a withdrawal that is waiting, and its money is released to the user. */
    @PostMapping(value = {"/withdrawals/{id}/reject", "/payouts/{id}/reject"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public WithdrawalResponse reject(@PathVariable long id, @Valid @RequestBody Decision body, Caller caller) {
        return withdrawals.reject(caller, id, body.reason());
    }

    /** The user cancels their own withdrawal while it is waiting, and its money is released. */
    @PostMapping(value = "/withdrawals/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public WithdrawalResponse cancel(@PathVariable long id, Caller caller) {
        return withdrawals.cancel(caller.userId(), id);
    }
}
