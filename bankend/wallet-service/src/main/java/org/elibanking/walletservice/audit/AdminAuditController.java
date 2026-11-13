package org.elibanking.walletservice.audit;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {
    public record AuditEntry(long id, Instant occurredAt, String actor, String action, String entityType,
                             String entityId, String correlationId, String details, String source) {}
    public record Page(List<AuditEntry> entries, String nextCursor) {}
    private record Offset(Instant occurredAt, Long id) {}
    private record Cursor(Offset account, Offset auth, Offset transaction, Offset wallet) {}

    private final JdbcTemplate jdbc;
    private final RestClient accountClient;
    private final RestClient authClient;
    private final RestClient transactionClient;
    private final ObjectMapper mapper;

    public AdminAuditController(JdbcTemplate jdbc,
                                @Value("${account-service.url}") String accountUrl,
                                @Value("${audit-services.auth-url}") String authUrl,
                                @Value("${transaction-service.url}") String transactionUrl,
                                @Qualifier("accountServiceTokens") ServiceTokens accountTokens,
                                @Qualifier("authServiceTokens") ServiceTokens authTokens,
                                @Qualifier("transactionServiceTokens") ServiceTokens transactionTokens,
                                ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.accountClient = serviceClient(accountUrl, accountTokens);
        this.authClient = serviceClient(authUrl, authTokens);
        this.transactionClient = serviceClient(transactionUrl, transactionTokens);
    }

    @GetMapping
    public Page list(@RequestParam(required = false) String cursor,
                     @RequestParam(defaultValue = "50") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        Cursor offsets = decode(cursor);
        List<AuditEntry> account = fetch(accountClient, offsets.account());
        List<AuditEntry> auth = fetch(authClient, offsets.auth());
        List<AuditEntry> transaction = fetch(transactionClient, offsets.transaction());
        List<AuditEntry> wallet = local(offsets.wallet());
        List<AuditEntry> merged = new ArrayList<>(account.size() + auth.size() + transaction.size() + wallet.size());
        merged.addAll(account); merged.addAll(auth); merged.addAll(transaction); merged.addAll(wallet);
        merged.sort(Comparator.comparing(AuditEntry::occurredAt).reversed()
                .thenComparing(AuditEntry::source).thenComparing(Comparator.comparingLong(AuditEntry::id).reversed()));
        List<AuditEntry> page = merged.stream().limit(size).toList();
        if (page.isEmpty()) return new Page(page, null);
        Map<String, Offset> advanced = new HashMap<>();
        for (AuditEntry entry : page) advanced.put(entry.source(), new Offset(entry.occurredAt(), entry.id()));
        Cursor next = new Cursor(advanced.getOrDefault("account-service", offsets.account()),
                advanced.getOrDefault("auth-service", offsets.auth()),
                advanced.getOrDefault("transaction-service", offsets.transaction()),
                advanced.getOrDefault("wallet-service", offsets.wallet()));
        return new Page(page, encode(next));
    }

    private List<AuditEntry> fetch(RestClient client, Offset before) {
        var request = client.get().uri(uri -> {
            var builder = uri.path("/internal/v1/audit").queryParam("limit", 100);
            if (before != null) builder.queryParam("beforeAt", before.occurredAt()).queryParam("beforeId", before.id());
            return builder.build();
        });
        RequestIdContext.current().ifPresent(id -> request.header(RequestIdContext.HEADER, id));
        List<RemoteEntry> rows = request.retrieve().body(new ParameterizedTypeReference<>() {});
        return rows == null ? List.of() : rows.stream().map(r -> new AuditEntry(r.id(), r.occurredAt(), r.actor(),
                r.action(), r.entityType(), r.entityId(), r.correlationId(), r.details(), r.source())).toList();
    }

    private record RemoteEntry(long id, Instant occurredAt, String actor, String action, String entityType,
                                String entityId, String correlationId, String details, String source) {}

    private List<AuditEntry> local(Offset before) {
        java.sql.Timestamp cutoff = before == null ? null : java.sql.Timestamp.from(before.occurredAt());
        Long beforeId = before == null ? null : before.id();
        return jdbc.query("SELECT id, occurred_at, actor, action, entity_type, entity_id, correlation_id, " +
                        "CAST(details AS CHAR) details FROM audit_log WHERE (? IS NULL OR occurred_at < ? " +
                        "OR (occurred_at = ? AND id < ?)) ORDER BY occurred_at DESC, id DESC LIMIT 100",
                (rs, n) -> new AuditEntry(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor"), rs.getString("action"), rs.getString("entity_type"),
                        rs.getString("entity_id"), rs.getString("correlation_id"), rs.getString("details"), "wallet-service"),
                cutoff, cutoff, cutoff, beforeId);
    }

    private Cursor decode(String value) {
        if (value == null || value.isBlank()) return new Cursor(null, null, null, null);
        try {
            return mapper.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid audit cursor");
        }
    }
    private String encode(Cursor cursor) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(cursor)); }
        catch (Exception e) { throw new IllegalStateException("Could not encode audit cursor", e); }
    }
    private RestClient serviceClient(String url, ServiceTokens tokens) {
        return RestClient.builder().baseUrl(url).requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(tokens.token());
            return execution.execute(request, body);
        }).build();
    }
}
