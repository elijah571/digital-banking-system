package org.elibanking.walletservice.withdrawal;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/wallet/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawals;

    public WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    /** 202 with the REQUESTED payout once its money is held. A repeat with the same key gets the same answer. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WithdrawalRequest body,
            Caller caller) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new RequestRefusedException(HttpStatus.BAD_REQUEST,
                    "An Idempotency-Key header of 1 to 255 characters is required");
        }
        WithdrawalService.Reply reply = withdrawals.request(caller.userId(), key, body);
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    /** One of the caller's withdrawals, with its status. Another user's withdrawal is 404. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WithdrawalResponse get(@PathVariable long id, Caller caller) {
        return withdrawals.view(caller.userId(), id);
    }
}
