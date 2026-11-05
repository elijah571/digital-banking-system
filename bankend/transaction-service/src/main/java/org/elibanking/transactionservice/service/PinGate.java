package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.exception.TransactionAccessDeniedException;
import org.elibanking.transactionservice.security.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Every transfer path passes through here (wallet-plan 8.22). While {@code TRANSFER_PIN_REQUIRED} is off, it checks nothing and
 * calls nothing, so the verifier is never reached.
 */
@Component
public class PinGate {

    private final PinVerifier verifier;
    private final boolean required;

    public PinGate(PinVerifier verifier, @Value("${transfer.pin-required:false}") boolean required) {
        this.verifier = verifier;
        this.required = required;
    }

    public void check(Caller caller, String pin) {
        if (!required) {
            return;
        }
        if (caller.userId() == null) {
            throw new TransactionAccessDeniedException("A transfer needs a user whose PIN is checked");
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN is required for transfers");
        }
        verifier.check(caller.userId(), pin);
    }
}
