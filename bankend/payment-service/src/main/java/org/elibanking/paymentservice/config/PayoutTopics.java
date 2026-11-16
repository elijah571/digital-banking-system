package org.elibanking.paymentservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Provision payout command and lifecycle topics before listeners/outbox relays use them. */
@Configuration
public class PayoutTopics {
    @Bean NewTopic payoutRequestedTopic() { return topic("payout.requested"); }
    @Bean NewTopic payoutProcessingTopic() { return topic("payment.payout.processing"); }
    @Bean NewTopic payoutAmbiguousTopic() { return topic("payment.payout.ambiguous"); }
    @Bean NewTopic payoutRetryingTopic() { return topic("payment.payout.retrying"); }
    @Bean NewTopic payoutReviewRequiredTopic() { return topic("payment.payout.review_required"); }
    @Bean NewTopic payoutUpdatedTopic() { return topic("payment.payout.updated"); }
    @Bean NewTopic payoutOperationsAlertTopic() { return topic("notification.payout.review_required"); }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
