package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

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
}
