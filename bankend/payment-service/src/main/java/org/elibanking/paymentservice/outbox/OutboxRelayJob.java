package org.elibanking.paymentservice.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the outbox relay on a schedule. Tests turn it off and call the relay themselves, so runs are deterministic. */
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
