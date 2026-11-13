package org.elibanking.notificationservice.service;

import org.springframework.web.util.HtmlUtils;

import java.util.List;

/** Branded, escaped HTML and plain-text customer email bodies. */
public final class CustomerEmailTemplates {

    private static final String BRAND = "Eli Digital Bank";

    private CustomerEmailTemplates() {
    }

    public record Email(String subject, String html, String plainText) {
    }

    public static Email emailVerification(String name, String link, String expiresIn) {
        return message("Verify your email", "Verify your Eli Digital Bank email",
                greeting(name) + paragraph("Welcome to Eli Digital Bank. Confirm your email address to activate your account.")
                        + button(link, "Verify email")
                        + note("This link expires in " + safe(expiresIn) + ". If you did not create an account, you can ignore this email."),
                "Welcome to Eli Digital Bank. Verify your email: " + link + "\nThis link expires in " + expiresIn
                        + ". If you did not create an account, ignore this email.");
    }

    public static Email passwordReset(String name, String link, String token, String expiresIn) {
        return message("Reset your password", "Reset your Eli Digital Bank password",
                greeting(name) + paragraph("We received a request to reset your password.")
                        + button(link, "Choose a new password")
                        + paragraph("If prompted, enter this reset code:")
                        + code(token)
                        + note("This code expires in " + safe(expiresIn)
                                + ". If you did not request a reset, ignore this email. Your password has not changed."),
                "We received a request to reset your password.\nOpen: " + link + "\nReset code: " + token
                        + "\nThis code expires in " + expiresIn + ". If you did not request a reset, ignore this email.");
    }

    public static Email passwordChanged(String name) {
        return message("Password changed", "Your password was changed",
                greeting(name) + paragraph("The password for your Eli Digital Bank account was changed. All active sessions were signed out.")
                        + warning("If this wasn't you, reset your password immediately and contact support."),
                "The password for your Eli Digital Bank account was changed and all active sessions were signed out. "
                        + "If this wasn't you, reset your password immediately and contact support.");
    }

    public static Email pinChanged(String name) {
        return message("Transfer PIN changed", "Your transfer PIN was changed",
                greeting(name) + paragraph("The transfer PIN on your Eli Digital Bank account was just set or changed.")
                        + warning("If this wasn't you, contact support immediately."),
                "The transfer PIN on your Eli Digital Bank account was just set or changed. "
                        + "If this wasn't you, contact support immediately.");
    }

    public static Email otp(String name, String code, String purpose, String expiresIn) {
        return message("Your one-time code", "Confirm your security code",
                greeting(name) + paragraph("Use this one-time code to " + safe(purpose) + ".")
                        + code(code)
                        + warning("This code expires in " + safe(expiresIn)
                                + ". Eli staff will never ask you to share it. If you did not request it, ignore this email."),
                "Use this one-time code to " + purpose + ": " + code + ". It expires in " + expiresIn
                        + ". Never share it with anyone. If you did not request it, ignore this email.");
    }

    public static Email payment(String status, String amount, String currency, String reference, String reason) {
        boolean successful = "successful".equalsIgnoreCase(status);
        String title = successful ? "Payment successful" : "Payment not completed";
        String intro = successful
                ? "Your payment has been processed."
                : "We could not complete your payment.";
        return moneyMovement(title, "Payment update from Eli Digital Bank", intro, amount, currency,
                List.of(row("Payment reference", reference), row("Details", reason)),
                successful ? "If you do not recognize this payment, contact support." : "You can try again or contact support if you need help.");
    }

    public static Email deposit(String status, String amount, String currency, String accountNumber,
                                String reference, String reason) {
        boolean successful = "successful".equalsIgnoreCase(status);
        String title = successful ? "Deposit received" : "Deposit not completed";
        return moneyMovement(title, "Deposit update from Eli Digital Bank",
                successful ? "Your account has been credited." : "We could not complete your card deposit.",
                amount, currency, List.of(row("Account", maskedAccount(accountNumber)),
                        row("Deposit reference", reference), row("Details", reason)),
                successful ? "If you do not recognize this deposit, contact support." : "If a card was charged, contact support with your deposit reference.");
    }

    public static Email withdrawal(String status, String amount, String currency, String accountNumber,
                                   String destination, String reference, String reason) {
        String title = "Withdrawal " + statusLabel(status);
        return moneyMovement(title, "Withdrawal update from Eli Digital Bank",
                "Here are the details of your withdrawal request.",
                amount, currency, List.of(row("From account", maskedAccount(accountNumber)),
                        row("Destination", destination), row("Reference", reference), row("Details", reason)),
                "If you did not request this withdrawal, contact support immediately.");
    }

    public static Email transfer(String status, String amount, String currency, String accountNumber,
                                 String counterparty, String reference, String reason) {
        String title = "Transfer " + statusLabel(status);
        return moneyMovement(title, "Transfer update from Eli Digital Bank",
                "Here are the details of your transfer.",
                amount, currency, List.of(row("Account", maskedAccount(accountNumber)),
                        row("Recipient", counterparty), row("Reference", reference), row("Details", reason)),
                "If you do not recognize this transfer, contact support immediately.");
    }

    public static Email fraudAlert(String accountNumber, String amount, String reference, String reason) {
        String title = "Security alert";
        return message(title, "Important security alert for your account",
                warning("We detected activity that may need your attention. Review the details below and contact support if you do not recognize it.")
                        + details(List.of(row("Account", maskedAccount(accountNumber)), row("Amount", amount),
                        row("Reference", reference), row("Reason", reason)))
                        + warning("Never share your password, transaction PIN, or one-time code with anyone."),
                "Important security alert. Account: " + maskedAccount(accountNumber) + ". Amount: " + amount
                        + ". Reference: " + reference + ". Reason: " + reason
                        + ". If you do not recognize this activity, contact support. Never share your password, PIN, or one-time code.");
    }

    private static Email moneyMovement(String heading, String subject, String intro, String amount, String currency,
                                       List<String[]> rows, String footer) {
        String displayAmount = safe(amount) + " " + safe(currency);
        return message(heading, subject,
                paragraph(intro) + "<div style=\"margin:20px 0;padding:18px;background:#f8f7f4;border-radius:10px;text-align:center\">"
                        + "<div style=\"font-size:12px;color:#77746c\">AMOUNT</div>"
                        + "<div style=\"margin-top:6px;font-size:26px;font-weight:bold;color:#171717\">" + displayAmount + "</div></div>"
                        + details(rows) + note(footer),
                intro + "\nAmount: " + displayAmount + "\n"
                        + rows.stream().map(row -> row[0] + ": " + row[1]).reduce("", (a, b) -> a + b + "\n")
                        + footer);
    }

    private static Email message(String heading, String subject, String body, String plainText) {
        String html = "<!doctype html><html lang=\"en\"><body style=\"margin:0;padding:24px;background:#f5f3ed;color:#171717;"
                + "font-family:Arial,Helvetica,sans-serif\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:600px;margin:0 auto;"
                + "background:#fff;border:1px solid #e2e0d9;border-radius:14px\"><tr><td style=\"padding:24px 28px;background:#171717;"
                + "border-radius:14px 14px 0 0\"><span style=\"display:inline-block;padding:8px 11px;background:#d6a928;"
                + "border-radius:8px;font-size:16px;font-weight:bold;color:#171717\">E</span><span style=\"margin-left:10px;"
                + "font-size:12px;font-weight:bold;letter-spacing:2px;color:#fff\">ELI DIGITAL BANK</span></td></tr>"
                + "<tr><td style=\"padding:28px\"><h1 style=\"margin:0 0 18px;font-size:22px;line-height:1.3\">" + safe(heading)
                + "</h1>" + body + "</td></tr><tr><td style=\"padding:18px 28px;border-top:1px solid #e2e0d9;color:#77746c;"
                + "font-size:11px;line-height:1.6\">This is an automated account notification. Please do not reply to this email."
                + "<br>Keep your account credentials private. &copy; Eli Digital Bank</td></tr></table></body></html>";
        return new Email(subject, html, plainText);
    }

    private static String greeting(String name) {
        return paragraph("Hello " + safe(name) + ",");
    }

    private static String paragraph(String text) {
        return "<p style=\"margin:0 0 14px;font-size:14px;line-height:1.65;color:#302e29\">" + text + "</p>";
    }

    private static String note(String text) {
        return "<p style=\"margin:18px 0 0;padding:12px;background:#fbf7ea;border-radius:8px;font-size:12px;"
                + "line-height:1.6;color:#5d5a53\">" + text + "</p>";
    }

    private static String warning(String text) {
        return "<p style=\"margin:14px 0;padding:12px;border-left:3px solid #d6a928;background:#fbf7ea;font-size:13px;"
                + "line-height:1.6;color:#46433d\">" + safe(text) + "</p>";
    }

    private static String details(List<String[]> rows) {
        StringBuilder html = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\""
                + " style=\"margin:14px 0;border-collapse:collapse\">");
        for (String[] row : rows) {
            html.append("<tr><td style=\"padding:9px 0;border-bottom:1px solid #f0efea;font-size:12px;color:#77746c\">")
                    .append(safe(row[0])).append("</td><td style=\"padding:9px 0;border-bottom:1px solid #f0efea;"
                            + "font-size:12px;font-weight:bold;text-align:right;color:#171717\">")
                    .append(safe(row[1])).append("</td></tr>");
        }
        return html.append("</table>").toString();
    }

    private static String[] row(String label, String value) {
        return new String[]{label, value == null || value.isBlank() ? "Not provided" : value};
    }

    private static String button(String link, String label) {
        String href = safeHttpLink(link);
        return "<p style=\"margin:20px 0\"><a href=\"" + href + "\" style=\"display:inline-block;padding:12px 20px;"
                + "border-radius:8px;background:#d6a928;color:#171717;text-decoration:none;font-size:13px;font-weight:bold\">"
                + safe(label) + "</a></p><p style=\"font-size:11px;line-height:1.6;color:#77746c\">If the button does not work, copy this link:<br>"
                + href + "</p>";
    }

    private static String code(String value) {
        return "<div style=\"margin:18px 0;padding:16px;background:#f8f7f4;border-radius:10px;text-align:center;"
                + "font-family:monospace;font-size:24px;font-weight:bold;letter-spacing:5px;color:#171717\">" + safe(value) + "</div>";
    }

    private static String maskedAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "Not provided";
        }
        String digits = accountNumber.replaceAll("\\s", "");
        return "•••• " + digits.substring(Math.max(0, digits.length() - 4));
    }

    private static String safeHttpLink(String link) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(link == null ? "" : link);
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("Email action link must be a valid HTTP or HTTPS URL", invalidUri);
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Email action link must use HTTP or HTTPS");
        }
        return safe(link);
    }

    private static String statusLabel(String status) {
        if (status == null) {
            return "updated";
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "successful", "failed", "pending", "processing", "rejected", "reversed", "refunded", "received" ->
                    status.toLowerCase(java.util.Locale.ROOT);
            default -> "updated";
        };
    }

    private static String safe(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
