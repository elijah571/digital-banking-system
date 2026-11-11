package org.elibanking.paymentservice.payout;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flutterwave V3's Nigerian bank transfer adapter (wallet-plan 9.4). A received 4xx that is a conclusive rejection becomes FAILED so
 * the hold is released; ambiguous answers (timeout, rate limit, duplicate reference) are allowed to reach the caller so recovery can
 * verify by reference before any retry. Amounts are NGN naira major units (D32); status is verified by our own reference (D33).
 */
public class FlutterwavePayoutProvider implements PayoutProvider {

    private final RestClient client;

    public FlutterwavePayoutProvider(RestClient client) {
        this.client = client;
    }

    @Override
    public List<Bank> listBanks() {
        JsonNode data = requiredData(client.get().uri("/banks/NG").retrieve().body(JsonNode.class));
        if (!data.isArray()) {
            throw new IllegalStateException("Flutterwave bank list response was not an array");
        }
        List<Bank> banks = new ArrayList<>();
        for (JsonNode bank : data) {
            String name = text(bank, "name");
            String code = text(bank, "code");
            if (name != null && code != null) {
                banks.add(new Bank(name, code));
            }
        }
        return List.copyOf(banks);
    }

    @Override
    public Resolution resolveAccount(String bankCode, String accountNumber) {
        try {
            JsonNode envelope = client.post().uri("/accounts/resolve").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("account_number", accountNumber, "account_bank", bankCode))
                    .retrieve().body(JsonNode.class);
            if (envelope == null || !"success".equalsIgnoreCase(text(envelope, "status"))) {
                return new Resolution(Outcome.NOT_FOUND, null);
            }
            String name = text(envelope.path("data"), "account_name");
            return name == null || name.isBlank() ? new Resolution(Outcome.NOT_FOUND, null)
                    : new Resolution(Outcome.FOUND, name);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.NotFound
                 | HttpClientErrorException.UnprocessableEntity notFound) {
            return new Resolution(Outcome.NOT_FOUND, null);
        }
    }

    @Override
    public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName,
                           String narration) {
        String reference = PayoutReference.fromPayoutKey(key);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("payout amount must be positive");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_bank", bankCode);
        body.put("account_number", accountNumber);
        body.put("amount", amount);          // NGN naira major units (D32); BigDecimal keeps it off floating point
        body.put("currency", "NGN");
        body.put("debit_currency", "NGN");
        body.put("reference", reference);
        body.put("narration", narration == null || narration.isBlank() ? "Bank transfer" : narration);
        final JsonNode data;
        try {
            data = requiredData(client.post().uri("/transfers").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class));
        } catch (HttpClientErrorException rejected) {
            if (isDefinitiveRejection(rejected)) {
                return new SendResult(reference, SendStatus.FAILED);
            }
            throw rejected;
        }
        SendStatus sendStatus = switch (lower(text(data, "status"))) {
            case "successful" -> SendStatus.ACCEPTED;
            case "new", "pending" -> SendStatus.PENDING;
            case "failed" -> SendStatus.FAILED;
            default -> SendStatus.PENDING;
        };
        JsonNode id = data.get("id");
        String providerRef = id == null || id.isNull() ? reference : id.asText();
        return new SendResult(providerRef, sendStatus);
    }

    @Override
    public PayoutStatus getStatus(String key) {
        return getStatusDetails(key).status();
    }

    @Override
    public StatusResult getStatusDetails(String key) {
        String reference = PayoutReference.fromPayoutKey(key);
        JsonNode envelope = client.get()
                .uri(uri -> uri.path("/transfers").queryParam("reference", reference).build())
                .retrieve().body(JsonNode.class);
        if (envelope == null || !"success".equalsIgnoreCase(text(envelope, "status"))) {
            throw new IllegalStateException("Flutterwave transfer query failed");
        }
        JsonNode data = envelope.path("data");
        JsonNode match = null;
        if (data.isArray()) {
            for (JsonNode transfer : data) {
                if (reference.equals(text(transfer, "reference"))) {
                    match = transfer;
                    break;
                }
            }
        } else if (reference.equals(text(data, "reference"))) {
            match = data;
        }
        if (match == null) {
            return new StatusResult(PayoutStatus.NOT_FOUND, key, null, null);
        }
        PayoutStatus status = switch (lower(text(match, "status"))) {
            case "successful" -> PayoutStatus.SUCCESS;
            case "failed" -> PayoutStatus.FAILED;
            case "reversed" -> PayoutStatus.REVERSED;
            case "new", "pending" -> PayoutStatus.PENDING;
            default -> PayoutStatus.PENDING;
        };
        // reference returned to wallet is the internal key (out-{id}); wallet matches it against its own ledger key (8.3).
        return new StatusResult(status, key, amount(match.get("amount")), text(match, "currency"));
    }

    /**
     * A 4xx is a conclusive rejection unless it can describe an already-created transfer (duplicate reference) or a transient
     * limit/timeout. Those ambiguous cases stay PROCESSING so recovery verifies the reference before any retry.
     */
    private static boolean isDefinitiveRejection(HttpClientErrorException error) {
        int status = error.getStatusCode().value();
        if (status < 400 || status >= 500 || status == 408 || status == 409 || status == 425 || status == 429) {
            return false;
        }
        String message = error.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return !(message.contains("reference")
                && (message.contains("already") || message.contains("duplicate") || message.contains("exist")));
    }

    private static JsonNode requiredData(JsonNode envelope) {
        if (envelope == null || !"success".equalsIgnoreCase(text(envelope, "status")) || envelope.path("data").isMissingNode()) {
            String message = envelope == null ? "empty response" : envelope.path("message").asText("request failed");
            throw new IllegalStateException("Flutterwave request failed: " + message);
        }
        return envelope.path("data");
    }

    private static BigDecimal amount(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.isNumber() ? node.asText() : node.asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
