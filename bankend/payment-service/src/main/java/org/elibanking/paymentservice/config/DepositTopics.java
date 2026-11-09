package org.elibanking.paymentservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The deposit topics, so the broker has them before the first event (wallet-plan 3.8). */
@Configuration
public class DepositTopics {

    @Bean
    NewTopic intentSucceededTopic() {
        return TopicBuilder.name("payment.intent.succeeded").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic intentFailedTopic() {
        return TopicBuilder.name("payment.intent.failed").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic payoutSuccessTopic() {
        return TopicBuilder.name("payment.payout.success").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic payoutFailedTopic() {
        return TopicBuilder.name("payment.payout.failed").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic payoutReversedTopic() {
        return TopicBuilder.name("payment.payout.reversed").partitions(1).replicas(1).build();
    }
}
