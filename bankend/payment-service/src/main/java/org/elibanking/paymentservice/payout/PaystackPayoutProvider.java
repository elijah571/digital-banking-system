package org.elibanking.paymentservice.payout;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Paystack's Nigerian bank transfer adapter. Provider failures are allowed to reach the caller for retry. */
public class PaystackPayoutProvider implements PayoutProvider {

    private final RestClient client;
    private final PaystackRecipientRepository recipients;

    public PaystackPayoutProvider(RestClient client, PaystackRecipientRepository recipients) {
        this.client = client;
        this.recipients = recipients;
    }

    @Override
    public List<Bank> listBanks() {
        List<Bank> banks = new ArrayList<>();
        for (int page = 1; ; page++) {
            int currentPage = page;
            JsonNode data = requiredData(client.get().uri(uri -> uri.path("/bank").queryParam("currency", "NGN")
                            .queryParam("country", "nigeria").queryParam("perPage", 100).queryParam("page", currentPage).build())
                    .retrieve().body(JsonNode.class));
            if (!data.isArray()) {
                throw new IllegalStateException("Paystack bank list response was not an array");
            }
            for (JsonNode bank : data) {
                String name = text(bank, "name");
                String code = text(bank, "code");
                if (name != null && code != null) {
                    banks.add(new Bank(name, code));
                }
            }
            if (data.size() < 100) {
                break;
            }
        }
        return List.copyOf(banks);
    }

    @Override
    public Resolution resolveAccount(String bankCode, String accountNumber) {
        try {
            JsonNode envelope = client.get().uri(uri -> uri.path("/bank/resolve")
                            .queryParam("account_number", accountNumber).queryParam("bank_code", bankCode).build())
                    .retrieve().body(JsonNode.class);
            if (envelope != null && !envelope.path("status").asBoolean(false)) {
                return new Resolution(Outcome.NOT_FOUND, null);
            }
            JsonNode data = requiredData(envelope);
            String name = text(data, "account_name");
            return name == null ? new Resolution(Outcome.NOT_FOUND, null) : new Resolution(Outcome.FOUND, name);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.NotFound notFound) {
            return new Resolution(Outcome.NOT_FOUND, null);
        }
    }

    @Override
    public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName,
                           String narration) {
        String reference = PaystackReference.fromPayoutKey(key);
        long kobo = amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (kobo <= 0) {
            throw new IllegalArgumentException("payout amount must be positive");
        }
        String recipientCode = recipients.find(bankCode, accountNumber)
                .orElseGet(() -> createRecipient(bankCode, accountNumber, accountName));
        JsonNode data = requiredData(client.post().uri("/transfer").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("source", "balance", "amount", kobo, "recipient", recipientCode,
                        "reference", reference, "reason", narration == null ? "Bank transfer" : narration,
                        "currency", "NGN"))
                .retrieve().body(JsonNode.class));
        String status = text(data, "status");
        SendStatus sendStatus = switch (status == null ? "pending" : status.toLowerCase()) {
            case "success", "pending", "otp" -> SendStatus.PENDING;
            case "failed", "reversed" -> SendStatus.FAILED;
            default -> SendStatus.PENDING;
        };
        String providerRef = text(data, "transfer_code");
        return new SendResult(providerRef == null ? reference : providerRef, sendStatus);
    }

    @Override
    public PayoutStatus getStatus(String key) {
        return getStatusDetails(key).status();
    }

    @Override
    public StatusResult getStatusDetails(String key) {
        String reference = PaystackReference.fromPayoutKey(key);
        final JsonNode response;
        try {
            response = client.get().uri("/transfer/verify/{reference}", reference).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound missing) {
            return new StatusResult(PayoutStatus.NOT_FOUND, key, null, null);
        }
        if (response != null && !response.path("status").asBoolean(false)
                && response.path("message").asText("").toLowerCase().contains("not found")) {
            return new StatusResult(PayoutStatus.NOT_FOUND, key, null, null);
        }
        JsonNode data = requiredData(response);
        String returnedReference = text(data, "reference");
        if (!reference.equals(returnedReference)) {
            throw new IllegalStateException("Paystack transfer reference did not match the requested payout");
        }
        PayoutStatus status = switch (String.valueOf(text(data, "status")).toLowerCase()) {
            case "success" -> PayoutStatus.SUCCESS;
            case "failed" -> PayoutStatus.FAILED;
            case "reversed" -> PayoutStatus.REVERSED;
            case "pending", "otp" -> PayoutStatus.PENDING;
            default -> PayoutStatus.PENDING;
        };
        JsonNode amount = data.get("amount");
        BigDecimal naira = amount == null || !amount.canConvertToLong() ? null
                : BigDecimal.valueOf(amount.longValue(), 2);
        return new StatusResult(status, PaystackReference.toPayoutKey(returnedReference), naira, text(data, "currency"));
    }

    private String createRecipient(String bankCode, String accountNumber, String accountName) {
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("resolved account name is required to create a Paystack recipient");
        }
        JsonNode data = requiredData(client.post().uri("/transferrecipient").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "nuban", "name", accountName, "account_number", accountNumber,
                        "bank_code", bankCode, "currency", "NGN"))
                .retrieve().body(JsonNode.class));
        String recipientCode = text(data, "recipient_code");
        if (recipientCode == null || recipientCode.isBlank()) {
            throw new IllegalStateException("Paystack did not return a recipient code");
        }
        return recipients.saveIfAbsent(bankCode, accountNumber, recipientCode);
    }

    private static JsonNode requiredData(JsonNode envelope) {
        if (envelope == null || !envelope.path("status").asBoolean(false) || envelope.path("data").isMissingNode()) {
            String message = envelope == null ? "empty response" : envelope.path("message").asText("request failed");
            throw new IllegalStateException("Paystack request failed: " + message);
        }
        return envelope.path("data");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
