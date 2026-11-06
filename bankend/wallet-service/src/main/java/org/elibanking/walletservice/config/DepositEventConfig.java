package org.elibanking.walletservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.elibanking.walletservice.deposit.DepositSettlementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Deposit events (wallet-plan 3.5, 3.8). A record that fails to apply is retried every two seconds, without an end. It is never
 * skipped: a skipped Stripe outcome is money that is never credited or booked. A malformed record therefore blocks its partition,
 * which is loud, and the producer is fixed.
 */
@Configuration
public class DepositEventConfig {

    @Bean
    DefaultErrorHandler depositErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }

    @Bean
    NewTopic walletDepositCompletedTopic() {
        return TopicBuilder.name(DepositSettlementService.COMPLETED_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic walletDepositFailedTopic() {
        return TopicBuilder.name(DepositSettlementService.FAILED_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic walletDepositAlertTopic() {
        return TopicBuilder.name(DepositSettlementService.ALERT_TOPIC).partitions(1).replicas(1).build();
    }
}
