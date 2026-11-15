package org.elibanking.cryptoservice.client;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class CryptoFraudClient {
    private final RestClient client;
    public CryptoFraudClient(@Value("${fraud-service.url}") String baseUrl, @Qualifier("fraudServiceTokens") ServiceTokens tokens) {
        client=RestClient.builder().baseUrl(baseUrl).requestInterceptor((request,body,execution)->{request.getHeaders().setBearerAuth(tokens.token());RequestIdContext.apply(request.getHeaders());return execution.execute(request,body);}).build();
    }
    public boolean flagged(String userId,String assetKey,BigDecimal amount,BigDecimal availableBalance) {
        JsonNode answer=client.post().uri("/internal/v1/fraud/crypto-check").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId",userId,"assetKey",assetKey,"amount",amount.toPlainString(),"availableBalance",availableBalance.toPlainString()))
                .retrieve().body(JsonNode.class);
        if(answer==null||!answer.hasNonNull("fraud")) throw new IllegalStateException("Fraud decision unavailable");
        return answer.get("fraud").asBoolean();
    }
}
