package org.elibanking.notificationservice.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** Suppresses Kafka redelivery duplicates; a failed SMTP attempt releases its short claim for retry. */
@Component
public class CustomerEmailDelivery {
    private static final Duration CLAIM_TTL = Duration.ofMinutes(5);
    private static final Duration SENT_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final EmailSender sender;

    public CustomerEmailDelivery(StringRedisTemplate redis, EmailSender sender) {
        this.redis = redis;
        this.sender = sender;
    }

    public void send(Map<String, Object> event, String to, CustomerEmailTemplates.Email email) {
        String eventId = event.get("eventId") == null ? "" : event.get("eventId").toString();
        String sentKey = eventId.isBlank() ? null : "notification:email:sent:" + eventId;
        String claimKey = eventId.isBlank() ? null : "notification:email:claim:" + eventId;
        if (sentKey != null && Boolean.TRUE.equals(redis.hasKey(sentKey))) return;
        if (claimKey != null && !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(claimKey, "1", CLAIM_TTL))) return;
        if (sentKey != null && Boolean.TRUE.equals(redis.hasKey(sentKey))) {
            redis.delete(claimKey);
            return;
        }
        try {
            sender.send(to, email.subject(), email.html(), email.plainText());
            if (sentKey != null) redis.opsForValue().set(sentKey, "1", SENT_TTL);
        } catch (RuntimeException failure) {
            if (claimKey != null) redis.delete(claimKey);
            throw failure;
        }
        if (claimKey != null) redis.delete(claimKey);
    }
}
