package org.elibanking.transactionservice.service;

/**
 * Checks a user's transfer PIN (wallet-plan D2, 3.5). The real check runs in wallet-service, which phase 4 builds. Until then the
 * default implementation refuses, so transfers fail closed while {@code TRANSFER_PIN_REQUIRED} is on.
 */
public interface PinVerifier {

    /** Returns normally when the PIN is right. Throws when it is wrong, when the user has none, or when the check cannot run. */
    void check(String userId, String pin);
}
