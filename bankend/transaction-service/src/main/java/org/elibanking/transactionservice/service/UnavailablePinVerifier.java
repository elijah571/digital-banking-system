package org.elibanking.transactionservice.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Refuses every PIN check, so no transfer passes while the wallet PIN check is missing (wallet-plan 8.32). */
@Component
public class UnavailablePinVerifier implements PinVerifier {

    @Override
    public void check(String userId, String pin) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transfers that need a PIN are not available yet");
    }
}
