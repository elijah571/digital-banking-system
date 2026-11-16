package org.elibanking.walletservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates the wallet payout outbox topics before payout events are published. */
@Configuration
public class PayoutTopics {
    @Bean NewTopic payoutRequestedTopic() { return topic("payout.requested"); }
    @Bean NewTopic walletPayoutRequestedTopic() { return topic("wallet.payout.requested"); }
    @Bean NewTopic walletPayoutApprovalRequiredTopic() { return topic("wallet.payout.approval-required"); }
    @Bean NewTopic walletPayoutPendingApprovalTopic() { return topic("wallet.payout.pending_approval"); }
    @Bean NewTopic walletPayoutApprovedTopic() { return topic("wallet.payout.approved"); }
    @Bean NewTopic walletPayoutPendingProviderTopic() { return topic("wallet.payout.pending_provider"); }
    @Bean NewTopic walletPayoutProcessingTopic() { return topic("wallet.payout.processing"); }
    @Bean NewTopic walletPayoutAmbiguousTopic() { return topic("wallet.payout.ambiguous"); }
    @Bean NewTopic walletPayoutManualReviewTopic() { return topic("wallet.payout.requires_manual_review"); }
    @Bean NewTopic walletPayoutCompletedTopic() { return topic("wallet.payout.completed"); }
    @Bean NewTopic walletPayoutFailedTopic() { return topic("wallet.payout.failed"); }
    @Bean NewTopic walletPayoutRejectedTopic() { return topic("wallet.payout.rejected"); }
    @Bean NewTopic walletPayoutCancelledTopic() { return topic("wallet.payout.cancelled"); }
    @Bean NewTopic walletPayoutExpiredTopic() { return topic("wallet.payout.expired"); }
    @Bean NewTopic walletPayoutReversedTopic() { return topic("wallet.payout.reversed"); }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
