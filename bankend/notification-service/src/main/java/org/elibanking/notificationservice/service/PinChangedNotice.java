package org.elibanking.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Tells the owner that the transfer PIN was set or changed (wallet-plan 3.5, threat model D-P6). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinChangedNotice {

    private final EmailSender emailSender;

    @KafkaListener(topics = "wallet.pin.changed", groupId = "notification-service-group")
    public void consume(@Payload Map<String, Object> payload) {
        try {
            emailSender.send((String) payload.get("email"), "Your EliBanking PIN was changed",
                    "<p>The transfer PIN on your EliBanking account was just set or changed.</p>"
                            + "<p>If this wasn't you, contact us at once.</p>",
                    "The transfer PIN on your EliBanking account was just set or changed. "
                            + "If this wasn't you, contact us at once.");
        } catch (Exception e) {
            log.error("Error sending PIN change notice: {}", e.getMessage());
        }
    }
}
