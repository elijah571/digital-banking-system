package org.elibanking.transactionservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * OTP events have their own topic and never share one with a transfer (wallet-plan D6). Retention is one hour: a code lives five
 * minutes, and a consumer that is down for longer has no use for it.
 */
@Configuration
public class OtpTopicConfig {

    public static final String NOTIFICATION_OTP_TOPIC = "notification.otp";

    @Bean
    NewTopic notificationOtpTopic() {
        return TopicBuilder.name(NOTIFICATION_OTP_TOPIC)
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "3600000")
                .build();
    }
}
