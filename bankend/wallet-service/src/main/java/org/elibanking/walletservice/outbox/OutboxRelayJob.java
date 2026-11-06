package org.elibanking.walletservice.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the outbox relay on a schedule. The switch is read on each run, not when the bean is created: a test context that turns it
 * off must never publish to the broker it did not start (the dev broker is localhost:9092). Tests call the relay themselves.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private final OutboxRelay relay;
    private final Environment environment;

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:1000}")
    public void run() {
        if (environment.getProperty("outbox.relay.enabled", Boolean.class, true)) {
            relay.relay();
        }
    }
}
