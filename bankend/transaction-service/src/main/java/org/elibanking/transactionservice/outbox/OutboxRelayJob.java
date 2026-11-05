package org.elibanking.transactionservice.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the outbox relay on a schedule. Tests turn it off and call the relay themselves, so runs are deterministic. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private final OutboxRelay relay;

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:1000}")
    public void run() {
        relay.relay();
    }
}
