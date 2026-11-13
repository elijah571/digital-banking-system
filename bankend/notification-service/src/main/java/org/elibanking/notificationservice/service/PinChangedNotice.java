package org.elibanking.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.events.KafkaEventEnvelope;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Tells the owner that the transfer PIN was set or changed (wallet-plan 3.5, threat model D-P6). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinChangedNotice {

    private final CustomerEmailDelivery delivery;

    @KafkaListener(topics = "wallet.pin.changed", groupId = "notification-service-group")
    public void consume(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.pinChanged(
                payload.getOrDefault("fullName", "there").toString());
        delivery.send(event, String.valueOf(payload.getOrDefault("email", "")), email);
    }
}
