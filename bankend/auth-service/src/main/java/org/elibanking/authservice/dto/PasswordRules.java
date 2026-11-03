package org.elibanking.authservice.dto;

final class PasswordRules {
    static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d).+$";
    static final String MESSAGE = "Password must contain at least one letter and one digit";

    private PasswordRules() {
    }
}
