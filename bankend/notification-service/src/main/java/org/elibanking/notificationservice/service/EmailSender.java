package org.elibanking.notificationservice.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends multipart HTML and plain-text email through SMTP (Gmail by default). Message bodies are never written to logs.
 */
@Slf4j
@Component
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String username;
    private final String password;
    private final String from;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${notification.mail.from:}") String from) {
        this.mailSender = mailSender;
        this.username = username;
        this.password = password;
        this.from = from.isBlank() ? username : from;
        log.info("Email delivery: {}", isConfigured() ? "SMTP as " + username : "NOT configured — delivery disabled");
    }

    public void send(String to, String subject, String html, String plainText) {
        if (!isConfigured()) {
            log.info("EMAIL not sent to={} subject={} because SMTP is not configured", to, subject);
            return;
        }
        try {
            MimeMessage message = mailSender.getObject().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "Eli Digital Bank");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.getObject().send(message);
            log.info("Email sent to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to={} subject={}: {}", to, subject, e.getMessage());
            throw new IllegalStateException("Email delivery failed", e);
        }
    }

    private boolean isConfigured() {
        return !username.isBlank() && !password.isBlank();
    }
}
