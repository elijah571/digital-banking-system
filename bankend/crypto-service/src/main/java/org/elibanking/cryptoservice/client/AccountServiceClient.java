package org.elibanking.cryptoservice.client;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigInteger;
import java.util.List;

@Component
public class AccountServiceClient {
    public record Operation(String operation, String userId, String assetKey, BigInteger amountBaseUnits,
                            String feeAssetKey, BigInteger feeBaseUnits, String idempotencyKey, String reference) {}
    public record Balance(String assetKey, String availableBaseUnits, String reservedBaseUnits) {}
    public record OperationResult(long entryId, boolean replayed) {}

    private final RestClient client;
    public AccountServiceClient(@Value("${account-service.url}") String baseUrl,
            @Qualifier("accountServiceTokens") ServiceTokens tokens) {
        client = RestClient.builder().baseUrl(baseUrl).requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(tokens.token()); RequestIdContext.apply(request.getHeaders());
            return execution.execute(request, body);
        }).build();
    }
    public void registerAsset(String assetKey) {
        client.post().uri("/internal/v1/crypto/assets").contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("assetKey", assetKey)).retrieve().toBodilessEntity();
    }
    public OperationResult apply(Operation operation) {
        try { return client.post().uri("/internal/v1/crypto/operations").contentType(MediaType.APPLICATION_JSON)
                .body(operation).retrieve().body(OperationResult.class); }
        catch(HttpClientErrorException.UnprocessableEntity e){throw new InsufficientCryptoBalance();}
    }
    public List<Balance> balances(String userId) {
        return client.get().uri("/internal/v1/crypto/balances/{userId}", userId).retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() {});
    }
    public static final class InsufficientCryptoBalance extends RuntimeException {}
}
