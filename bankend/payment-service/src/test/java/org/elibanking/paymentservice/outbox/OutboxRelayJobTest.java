package org.elibanking.paymentservice.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayJobTest {

    @Test
    void readsTheEnabledSwitchEachTimeItRuns() {
        OutboxRelay relay = mock(OutboxRelay.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("outbox.relay.enabled", Boolean.class, true))
                .thenReturn(false, true);
        OutboxRelayJob job = new OutboxRelayJob(relay, environment);

        job.run();
        verify(relay, never()).relay();

        job.run();
        verify(relay).relay();
    }
}
