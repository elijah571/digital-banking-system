package org.elibanking.paymentservice.payout;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Monnify sandbox/live NGN payout adapter (wallet-plan 9.17). */
public final class MonnifyPayoutProvider implements PayoutProvider {

    private static final Comparator<Bank> BANK_ORDER = Comparator.comparing(Bank::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Bank::name).thenComparing(Bank::code);

    private final RestClient client;
    private final MonnifyTokenManager tokens;
    private final String sourceAccountNumber;

    public MonnifyPayoutProvider(RestClient client, MonnifyTokenManager tokens, String sourceAccountNumber) {
        if (sourceAccountNumber == null || !sourceAccountNumber.matches("\\d{10}")) {
            throw new IllegalArgumentException("MONNIFY_SOURCE_ACCOUNT_NUMBER must be a 10-digit account number");
        }
        this.client = client;
        this.tokens = tokens;
        this.sourceAccountNumber = sourceAccountNumber;
    }

    @Override
    public String getProviderName() {
        return "monnify";
    }

    @Override
    public List<Bank> listBanks() {
        JsonNode response = client.post().uri("/api/v1/sdk/transactions/banks").retrieve().body(JsonNode.class);
        JsonNode data = successfulBody(response);
        if (!data.isArray()) {
            throw new IllegalStateException("Monnify bank list response was not an array");
        }
        List<Bank> banks = new ArrayList<>();
        for (JsonNode item : data) {
            String name = text(item, "name");
            String code = text(item, "code");
            if (name != null && code != null) {
                banks.add(new Bank(name, code));
            }
        }
        banks.sort(BANK_ORDER);
        return List.copyOf(banks);
    }

    @Override
    public Resolution resolveAccount(String bankCode, String accountNumber) {
        try {
            JsonNode response = client.get()
                    .uri(uri -> uri.path("/api/v2/disbursements/account/validate")
                            .queryParam("accountNumber", accountNumber).queryParam("bankCode", bankCode).build())
                    .header("Authorization", "Bearer " + tokens.getToken())
                    .retrieve().body(JsonNode.class);
            JsonNode data = successfulBody(response);
            String name = text(data, "accountName");
            return name == null ? new Resolution(Outcome.NOT_FOUND, null) : new Resolution(Outcome.FOUND, name);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.NotFound notFound) {
            return new Resolution(Outcome.NOT_FOUND, null);
        }
    }

    @Override
    public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName,
                           String narration) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("payout amount must be positive");
        }
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("a validated destination account name is required");
        }
        String reference = PayoutReference.fromPayoutKey(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("reference", reference);
        body.put("narration", narration == null || narration.isBlank() ? "Bank transfer" : narration);
        body.put("destinationBankCode", bankCode);
        body.put("destinationAccountNumber", accountNumber);
        body.put("destinationAccountName", accountName);
        body.put("currency", "NGN");
        body.put("sourceAccountNumber", sourceAccountNumber);
        final JsonNode response;
        try {
            response = client.post().uri("/api/v2/disbursements/single")
                    .header("Authorization", "Bearer " + tokens.getToken())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException rejected) {
            int statusCode = rejected.getStatusCode().value();
            if (statusCode >= 400 && statusCode < 500 && statusCode != 409 && statusCode != 429) {
                return new SendResult(reference, SendStatus.FAILED);
            }
            throw rejected;
        }
        JsonNode data = successfulBody(response);
        String status = text(data, "status");
        SendStatus result = switch (status == null ? "" : status.toUpperCase()) {
            case "SUCCESS", "COMPLETED", "IN_PROGRESS", "AWAITING_PROCESSING" -> SendStatus.ACCEPTED;
            case "FAILED", "REVERSED", "EXPIRED" -> SendStatus.FAILED;
            // PENDING_AUTHORIZATION deliberately stays pending; an OTP must never cause a second transfer attempt.
            default -> SendStatus.PENDING;
        };
        String providerReference = text(data, "reference");
        return new SendResult(providerReference == null ? reference : providerReference, result);
    }

    @Override
    public PayoutStatus getStatus(String key) {
        return getStatusDetails(key).status();
    }

    @Override
    public StatusResult getStatusDetails(String key) {
        String reference = PayoutReference.fromPayoutKey(key);
        try {
            JsonNode response = client.get()
                    .uri(uri -> uri.path("/api/v2/disbursements/single/summary").queryParam("reference", reference).build())
                    .header("Authorization", "Bearer " + tokens.getToken())
                    .retrieve().body(JsonNode.class);
            if (response != null && "D02".equalsIgnoreCase(text(response, "responseCode"))) {
                return new StatusResult(PayoutStatus.NOT_FOUND, key, null, null);
            }
            JsonNode data = successfulBody(response);
            PayoutStatus status = mapStatus(text(data, "status"));
            return new StatusResult(status, key, decimal(data.get("amount")), text(data, "currency"));
        } catch (HttpClientErrorException.NotFound notFound) {
            return new StatusResult(PayoutStatus.NOT_FOUND, key, null, null);
        }
    }

    private static PayoutStatus mapStatus(String value) {
        if (value == null) return PayoutStatus.PENDING;
        return switch (value.toUpperCase()) {
            case "SUCCESS", "COMPLETED" -> PayoutStatus.SUCCESS;
            case "FAILED", "EXPIRED" -> PayoutStatus.FAILED;
            case "REVERSED" -> PayoutStatus.REVERSED;
            case "D02" -> PayoutStatus.NOT_FOUND;
            default -> PayoutStatus.PENDING;
        };
    }

    private static JsonNode successfulBody(JsonNode response) {
        if (response == null || !response.path("requestSuccessful").asBoolean(false)) {
            throw new IllegalStateException("Monnify returned an unsuccessful response");
        }
        JsonNode body = response.get("responseBody");
        if (body == null || body.isMissingNode() || body.isNull()) {
            throw new IllegalStateException("Monnify response did not contain responseBody");
        }
        return body;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        return value.asText();
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            BigDecimal amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

}
