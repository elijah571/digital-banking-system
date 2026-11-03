package org.elibanking.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/** Emails for the auth flows published by auth-service. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEmailListener {

    private final EmailSender emailSender;

    @KafkaListener(topics = "auth.email-verification.requested", groupId = "notification-service-group")
    public void onVerificationRequested(@Payload Map<String, Object> event) {
        String name = str(event, "fullName");
        String link = str(event, "link");
        String minutes = str(event, "expiresInMinutes");
        String html = layout("Verify your email",
                "<p>Hi " + esc(name) + ",</p>"
                        + "<p>Welcome to EliBanking! Please confirm your email address to activate your account.</p>"
                        + button(link, "Verify email")
                        + "<p style=\"color:#666;font-size:13px\">This link expires in " + hours(minutes) + ". "
                        + "If you did not create an account, you can ignore this email.</p>");
        emailSender.send(str(event, "email"), "Verify your EliBanking account", html,
                "Verify your email: " + link);
    }

    @KafkaListener(topics = "auth.password-reset.requested", groupId = "notification-service-group")
    public void onPasswordResetRequested(@Payload Map<String, Object> event) {
        String name = str(event, "fullName");
        String token = str(event, "token");
        String link = str(event, "link");
        String html = layout("Reset your password",
                "<p>Hi " + esc(name) + ",</p>"
                        + "<p>We received a request to reset your password.</p>"
                        + button(link, "Choose a new password")
                        + "<p>Or use this reset code in the app:</p>"
                        + "<p style=\"font-family:monospace;font-size:13px;background:#f3f4f6;padding:10px;word-break:break-all\">"
                        + esc(token) + "</p>"
                        + "<p style=\"color:#666;font-size:13px\">The code expires in " + esc(str(event, "expiresInMinutes"))
                        + " minutes. If you did not ask for this, ignore this email — your password stays the same.</p>");
        emailSender.send(str(event, "email"), "Reset your EliBanking password", html,
                "Password reset token: " + token + "\nLink: " + link);
    }

    @KafkaListener(topics = "auth.password.changed", groupId = "notification-service-group")
    public void onPasswordChanged(@Payload Map<String, Object> event) {
        String html = layout("Your password was changed",
                "<p>Hi " + esc(str(event, "fullName")) + ",</p>"
                        + "<p>The password for your EliBanking account was just changed and all sessions were signed out.</p>"
                        + "<p><b>If this wasn't you, reset your password immediately and contact support.</b></p>");
        emailSender.send(str(event, "email"), "Your EliBanking password was changed", html, "Password changed");
    }

    private static String layout(String title, String body) {
        return "<div style=\"font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#222\">"
                + "<h2 style=\"color:#1d4ed8\">" + esc(title) + "</h2>" + body
                + "<hr style=\"border:none;border-top:1px solid #eee\"><p style=\"color:#999;font-size:12px\">EliBanking</p></div>";
    }

    private static String button(String link, String label) {
        return "<p><a href=\"" + esc(link) + "\" style=\"display:inline-block;background:#1d4ed8;color:#fff;"
                + "padding:12px 20px;border-radius:6px;text-decoration:none\">" + esc(label) + "</a></p>"
                + "<p style=\"font-size:12px;color:#666\">Or open: " + esc(link) + "</p>";
    }

    private static String hours(String minutes) {
        try {
            long m = Long.parseLong(minutes);
            return m % 60 == 0 ? (m / 60) + " hours" : m + " minutes";
        } catch (NumberFormatException e) {
            return minutes + " minutes";
        }
    }

    private static String str(Map<String, Object> event, String key) {
        Object v = event.get(key);
        return v == null ? "" : v.toString();
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s);
    }
}
