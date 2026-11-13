package org.elibanking.accountservice.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

@RestController
@RequestMapping("/internal/v1/audit")
public class InternalAuditController {
    public record Entry(long id, Instant occurredAt, String actor, String action, String entityType,
                        String entityId, String correlationId, String details, String source) {}
    private final JdbcTemplate jdbc;
    public InternalAuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Entry> list(@RequestParam(required = false) Instant beforeAt,
                            @RequestParam(required = false) Long beforeId,
                            @RequestParam(defaultValue = "100") int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        Timestamp cutoff = beforeAt == null ? null : Timestamp.from(beforeAt);
        return jdbc.query("SELECT id, occurred_at, actor, action, entity_type, entity_id, correlation_id, " +
                        "CAST(details AS CHAR) details FROM audit_log WHERE (? IS NULL OR occurred_at < ? " +
                        "OR (occurred_at = ? AND id < ?)) ORDER BY occurred_at DESC, id DESC LIMIT ?", (rs, n) -> new Entry(rs.getLong("id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"), rs.getString("action"),
                        rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("correlation_id"),
                        rs.getString("details"), "account-service"), cutoff, cutoff, cutoff, beforeId, size);
    }
}
