package org.elibanking.paymentservice.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends an event to the outbox (wallet-plan 3.8). It runs only inside the transaction it describes (MANDATORY), so the event
 * commits or rolls back with the change, and {@link OutboxRelay} publishes it afterwards.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String messageKey, String aggregateId, Object event) {
        jdbc.update("INSERT INTO outbox (aggregate_id, topic, message_key, payload) VALUES (?, ?, ?, ?)",
                aggregateId, topic, messageKey, mapper.writeValueAsString(event));
    }
}
