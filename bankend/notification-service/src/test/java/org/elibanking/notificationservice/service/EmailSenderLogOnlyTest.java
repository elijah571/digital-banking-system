package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.internet.AddressException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmailSenderLogOnlyTest {

    @Test
    void logOnlyModeNeverConnectsToSmtpEvenWhenCredentialsAreConfigured() {
        ObjectProvider<JavaMailSender> mailSender = mock();
        EmailSender sender = new EmailSender(mailSender, "smtp-user", "smtp-password", "from@example.test",
                true, true);

        sender.send("customer@example.test", "Transfer complete", "<p>Done</p>", "Done");

        verify(mailSender, never()).getObject();
    }

    @Test
    void fromAddressAcceptsAConfiguredDisplayNameWithoutNestingIt() throws Exception {
        var address = EmailSender.parseFromAddress("ELI-PAY <no-reply@example.test>");

        org.junit.jupiter.api.Assertions.assertEquals("no-reply@example.test", address.getAddress());
        org.junit.jupiter.api.Assertions.assertEquals("ELI-PAY", address.getPersonal());
    }

    @Test
    void fromAddressRejectsMultipleMailboxes() {
        org.junit.jupiter.api.Assertions.assertThrows(AddressException.class,
                () -> EmailSender.parseFromAddress("first@example.test, second@example.test"));
    }
}
