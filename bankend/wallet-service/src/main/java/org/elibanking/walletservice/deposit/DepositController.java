package org.elibanking.walletservice.deposit;

import jakarta.validation.Valid;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Card deposits (wallet-plan 3.5). The user token is required by the security chain for every /api/** route. */
@RestController
@RequestMapping("/api/v1/wallet/deposits")
public class DepositController {

    private final DepositService deposits;

    public DepositController(DepositService deposits) {
        this.deposits = deposits;
    }

    /** Returns the PENDING deposit and the card intent's client secret. A repeat with the same key gets the same response. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DepositRequest body,
            Caller caller) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new RequestRefusedException(HttpStatus.BAD_REQUEST,
                    "An Idempotency-Key header of 1 to 255 characters is required");
        }
        DepositService.Reply reply = deposits.create(caller.userId(), key, body);
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }
}
