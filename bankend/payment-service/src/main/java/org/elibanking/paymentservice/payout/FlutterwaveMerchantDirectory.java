package org.elibanking.paymentservice.payout;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Lists all Flutterwave collection subaccounts, exposing only identifiers and business names. */
public class FlutterwaveMerchantDirectory implements MerchantDirectory {

    private static final int MAX_PAGES = 1000;
    private static final Comparator<Merchant> NAME_ORDER = Comparator
            .comparing(Merchant::businessName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Merchant::businessName)
            .thenComparing(Merchant::id);

    private final RestClient client;

    public FlutterwaveMerchantDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public List<Merchant> list() {
        JsonNode first = response(1);
        int totalPages = first.path("meta").path("page_info").path("total_pages").asInt(1);
        if (totalPages < 1 || totalPages > MAX_PAGES) {
            throw new IllegalStateException("Flutterwave merchant list returned an invalid page count");
        }

        List<Merchant> merchants = new ArrayList<>();
        append(first.path("data"), merchants);
        for (int page = 2; page <= totalPages; page++) {
            append(response(page).path("data"), merchants);
        }
        merchants.sort(NAME_ORDER);
        return List.copyOf(merchants);
    }

    private JsonNode response(int page) {
        JsonNode envelope = client.get().uri(uri -> uri.path("/subaccounts").queryParam("page", page).build())
                .retrieve().body(JsonNode.class);
        if (envelope == null || !"success".equalsIgnoreCase(envelope.path("status").asText())
                || !envelope.path("data").isArray()) {
            throw new IllegalStateException("Flutterwave merchant list response was invalid");
        }
        return envelope;
    }

    private static void append(JsonNode data, List<Merchant> merchants) {
        if (!data.isArray()) {
            throw new IllegalStateException("Flutterwave merchant page did not contain an array");
        }
        for (JsonNode value : data) {
            JsonNode id = value.get("id");
            String businessName = value.path("business_name").asText("").trim();
            if (id != null && !id.isNull() && !businessName.isBlank()) {
                merchants.add(new Merchant(id.asText(), businessName));
            }
        }
    }
}
