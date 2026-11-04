package org.elibanking.accountservice.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one {@code audit_log} row. It must run inside the business transaction it describes ({@code MANDATORY}), so
 * the change and its audit row commit or roll back together (wallet-plan 3.9).
 */
@Service
public class AuditLogWriter {

    private final JdbcTemplate jdbc;

    public AuditLogWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param detailsJson a JSON object as text, or {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String entityType, String entityId, String correlationId,
                       String detailsJson) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, correlation_id, details)"
                + " VALUES (?, ?, ?, ?, ?, ?)", actor, action, entityType, entityId, correlationId, detailsJson);
    }
}
