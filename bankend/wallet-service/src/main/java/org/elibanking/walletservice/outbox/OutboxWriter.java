package org.elibanking.walletservice.outbox;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends an event to the outbox (wallet-plan 3.8). It runs only inside the transaction it describes (MANDATORY), so the event
 * commits or rolls back with the change, and {@link OutboxRelay} publishes it afterwards.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String messageKey, String aggregateId, Object event) {
        jdbc.update("INSERT INTO outbox (aggregate_id, topic, message_key, payload) VALUES (?, ?, ?, ?)",
                aggregateId, topic, messageKey, mapper.writeValueAsString(event));
    }
}
