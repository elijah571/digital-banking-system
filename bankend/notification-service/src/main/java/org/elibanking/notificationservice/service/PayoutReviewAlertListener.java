package org.elibanking.notificationservice.service;

import org.elibanking.common.events.KafkaEventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/** Sends exhausted payout dispatches to the configured operations mailbox. */
@Component
public class PayoutReviewAlertListener {
    private final EmailSender emails;
    private final String operationsEmail;

    public PayoutReviewAlertListener(EmailSender emails,
                                     @Value("${notification.operations.email:}") String operationsEmail) {
        this.emails = emails;
        this.operationsEmail = operationsEmail;
    }

    @KafkaListener(topics = "notification.payout.review_required", groupId = "notification-service-group")
    public void receive(Map<String, Object> event) {
        if (operationsEmail.isBlank()) return;
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        String payout = String.valueOf(payload.getOrDefault("payoutKey", "unknown"));
        String provider = String.valueOf(payload.getOrDefault("providerName", "unknown"));
        String reason = String.valueOf(payload.getOrDefault("reason", "automated dispatch retries exhausted"));
        String html = "<h1>Payout requires manual review</h1><p>Payout: " + HtmlUtils.htmlEscape(payout)
                + "</p><p>Last provider: " + HtmlUtils.htmlEscape(provider) + "</p><p>Reason: "
                + HtmlUtils.htmlEscape(reason) + "</p>";
        String plain = "Payout requires manual review\nPayout: " + payout + "\nLast provider: " + provider + "\nReason: " + reason;
        emails.send(operationsEmail, "Payout requires manual review", html, plain);
    }
}
