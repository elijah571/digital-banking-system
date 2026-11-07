package org.elibanking.walletservice.payout;

import org.elibanking.walletservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one place a payout changes state (wallet-plan 8.3). Every move is checked against the table, and in the same transaction it
 * writes the audit row and the outbox event {@code wallet.payout.<state>}. A move into a give-back state also returns the amount to the
 * day's usage. Callers run inside their own transaction (MANDATORY), so a move and its audit and event commit or roll back together.
 */
@Service
public class PayoutTransitions {

    /** The day a payout's usage is counted on: the Nigerian business day (8.3, D13). */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Lagos");

    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;

    public PayoutTransitions(OutboxWriter outbox, JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requested(OutboundPayout payout, String actor) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details)"
                        + " VALUES (?, 'PAYOUT_REQUESTED', 'outbound_payout', ?, JSON_OBJECT('amount', ?, 'currency', ?))",
                actor, String.valueOf(payout.getId()), payout.getAmount().toPlainString(), payout.getCurrency());
        appendEvent(payout, null, PayoutState.REQUESTED, "outbound payout requested");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void move(OutboundPayout payout, PayoutState to, String reason) {
        PayoutState from = payout.getState();
        OutboundPayoutStateMachine.require(from, to);
        payout.setState(to);
        payout.setUpdatedAt(Instant.now());
        if (OutboundPayoutStateMachine.GIVE_BACK.contains(to) && !OutboundPayoutStateMachine.GIVE_BACK.contains(from)) {
            giveBack(payout);
        }
        audit(payout, from, to, reason);
        appendEvent(payout, from, to, reason);
    }

    private void appendEvent(OutboundPayout payout, PayoutState from, PayoutState to, String reason) {
        String key = payout.ledgerKey();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("type", "wallet.payout." + to.name().toLowerCase());
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payoutId", payout.getId());
        body.put("type", payout.getType().name());
        body.put("from", from == null ? null : from.name());
        body.put("to", to.name());
        body.put("reason", reason == null ? "" : reason);
        envelope.put("payload", body);
        outbox.append("wallet.payout." + to.name().toLowerCase(), key, key, envelope);
    }

    /** Returns the amount to the usage of the day the payout was opened. The usage is never taken below zero. */
    private void giveBack(OutboundPayout payout) {
        LocalDate day = LocalDate.ofInstant(payout.getCreatedAt(), BUSINESS_ZONE);
        int updated = jdbc.update("UPDATE outbound_daily_usage SET total = total - ?, version = version + 1"
                + " WHERE account_number = ? AND usage_day = ? AND total >= ?",
                payout.getAmount(), payout.getAccountNumber(), day, payout.getAmount());
        if (updated != 1) {
            throw new IllegalStateException("no daily usage to give back for payout " + payout.getId());
        }
    }

    private void audit(OutboundPayout payout, PayoutState from, PayoutState to, String reason) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details)"
                        + " VALUES ('wallet-service', ?, 'outbound_payout', ?, JSON_OBJECT('from', ?, 'to', ?, 'reason', ?))",
                "PAYOUT_" + to.name(), String.valueOf(payout.getId()), from.name(), to.name(), reason == null ? "" : reason);
    }
}
