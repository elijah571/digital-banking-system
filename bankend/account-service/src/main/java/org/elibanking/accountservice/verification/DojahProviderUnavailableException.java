package org.elibanking.accountservice.verification;

public class DojahProviderUnavailableException extends RuntimeException {
    public DojahProviderUnavailableException() {
        super("Identity verification is temporarily unavailable");
    }
}
