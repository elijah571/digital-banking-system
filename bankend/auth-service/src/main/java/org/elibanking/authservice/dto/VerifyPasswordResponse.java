package org.elibanking.authservice.dto;

/** Whether the password is the account's. A user that does not exist gives {@code false}, the same as a wrong password. */
public record VerifyPasswordResponse(boolean valid) {
}
