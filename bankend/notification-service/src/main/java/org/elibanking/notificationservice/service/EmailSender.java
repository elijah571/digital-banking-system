package org.elibanking.notificationservice.service;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends multipart HTML and plain-text email through SMTP. Bodies are logged only when the explicit local smoke-test
 * setting is enabled; production defaults to keeping message contents out of logs.
 */
@Slf4j
@Component
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String username;
    private final String password;
    private final String from;
    private final boolean logContent;
    private final boolean logOnly;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${notification.mail.from:}") String from,
                       @Value("${notification.mail.log-content:false}") boolean logContent,
                       @Value("${notification.mail.log-only:false}") boolean logOnly) {
        this.mailSender = mailSender;
        this.username = username;
        this.password = password;
        this.from = from.isBlank() ? username : from;
        this.logContent = logContent;
        this.logOnly = logOnly;
        log.info("Email delivery: {}", logOnly ? "log-only" : isConfigured() ? "SMTP as " + username
                : "NOT configured — delivery disabled");
    }

    public void send(String to, String subject, String html, String plainText) {
        if (logOnly) {
            log.info("Email logged to={} subject={}", to, subject);
            if (logContent) log.info("Email content to={} subject={} body={}", to, subject, plainText);
            return;
        }
        if (!isConfigured()) {
            log.info("EMAIL not sent to={} subject={} because SMTP is not configured", to, subject);
            return;
        }
        try {
            MimeMessage message = mailSender.getObject().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            InternetAddress sender = parseFromAddress(from);
            if (sender.getPersonal() == null || sender.getPersonal().isBlank()) {
                sender.setPersonal("Eli Digital Bank", "UTF-8");
            }
            helper.setFrom(sender);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.getObject().send(message);
            log.info("Email sent to={} subject={}", to, subject);
            if (logContent) log.info("Email content to={} subject={} body={}", to, subject, plainText);
        } catch (Exception e) {
            log.error("Failed to send email to={} subject={}: {}", to, subject, e.getMessage());
            throw new IllegalStateException("Email delivery failed", e);
        }
    }

    private boolean isConfigured() {
        return !username.isBlank() && !password.isBlank();
    }

    static InternetAddress parseFromAddress(String configuredFrom) throws AddressException {
        InternetAddress[] parsed = InternetAddress.parse(configuredFrom, true);
        if (parsed.length != 1) {
            throw new AddressException("MAIL_FROM must contain exactly one valid email address");
        }
        parsed[0].validate();
        return parsed[0];
    }
}
