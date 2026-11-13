package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerEmailTemplatesTest {

    @Test
    void rendersTheCustomerEmailTypesAsHtmlAndPlainText() {
        List<CustomerEmailTemplates.Email> emails = List.of(
                CustomerEmailTemplates.payment("successful", "2500.00", "NGN", "pay-1", ""),
                CustomerEmailTemplates.deposit("successful", "2500.00", "NGN", "1234567890", "dep-1", ""),
                CustomerEmailTemplates.withdrawal("pending", "2500.00", "NGN", "1234567890", "Access Bank", "out-1", ""),
                CustomerEmailTemplates.transfer("successful", "2500.00", "NGN", "1234567890", "Ada", "tx-1", ""),
                CustomerEmailTemplates.fraudAlert("1234567890", "2500.00 NGN", "tx-2", "Unusual activity"),
                CustomerEmailTemplates.emailVerification("Ada", "https://bank.example/verify?t=token", "30 minutes"),
                CustomerEmailTemplates.passwordReset("Ada", "https://bank.example/reset?t=token", "123456", "30 minutes"),
                CustomerEmailTemplates.passwordChanged("Ada"),
                CustomerEmailTemplates.pinChanged("Ada"),
                CustomerEmailTemplates.otp("Ada", "123456", "change your transfer PIN", "5 minutes")
        );

        assertAll(emails.stream().map(email -> () -> {
            assertFalse(email.subject().isBlank());
            assertTrue(email.html().contains("<!doctype html>"));
            assertTrue(email.html().contains("ELI DIGITAL BANK"));
            assertFalse(email.plainText().isBlank());
        }));
    }

    @Test
    void escapesCustomerControlledTemplateValuesAndMasksAccountNumbers() {
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.fraudAlert(
                "1234567890", "₦1,000", "ref-1", "<script>alert(1)</script>");

        assertTrue(email.html().contains("7890"));
        assertFalse(email.html().contains("1234567890"));
        assertTrue(email.html().contains("&lt;script&gt;"));
        assertTrue(email.html().contains("alert(1)"));
        assertFalse(email.html().contains("<script>"));
    }

    @Test
    void refusesUnsafeAuthenticationLinks() {
        assertThrows(IllegalArgumentException.class,
                () -> CustomerEmailTemplates.emailVerification("Ada", "javascript:alert(1)", "30 minutes"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerEmailTemplates.passwordReset("Ada", "https://user@bank.example/reset", "123456", "30 minutes"));
    }

    @Test
    void otpTemplateIncludesTheCodeOnlyInTheDeliveredMessage() {
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.otp(
                "Ada", "583019", "change your transfer PIN", "5 minutes");

        assertTrue(email.html().contains("583019"));
        assertTrue(email.plainText().contains("583019"));
        assertTrue(email.html().contains("Eli staff will never ask you to share it"));
    }
}
