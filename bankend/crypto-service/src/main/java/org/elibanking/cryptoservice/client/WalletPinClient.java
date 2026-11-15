package org.elibanking.cryptoservice.client;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
public class WalletPinClient {
    private final RestClient client;
    public WalletPinClient(@Value("${wallet-service.url}") String baseUrl, @Qualifier("walletServiceTokens") ServiceTokens tokens) {
        client=RestClient.builder().baseUrl(baseUrl).requestInterceptor((request,body,execution)->{request.getHeaders().setBearerAuth(tokens.token());RequestIdContext.apply(request.getHeaders());return execution.execute(request,body);}).build();
    }
    public void verify(String userId, String pin) {
        try {
            client.post().uri("/internal/v1/pin/verify").contentType(MediaType.APPLICATION_JSON)
                    .body(new PinRequest(userId,pin)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value()==423) throw new PinRejected("PIN is locked");
            if (e.getStatusCode().value()==403) throw new PinRejected("PIN was not accepted or is not set");
            throw new IllegalStateException("PIN verification is temporarily unavailable");
        } catch (RuntimeException e) { if (e instanceof PinRejected || e instanceof IllegalStateException) throw e; throw new IllegalStateException("PIN verification is temporarily unavailable"); }
    }
    private record PinRequest(String userId,String pin) {
        @Override public String toString(){return "PinRequest[userId="+userId+", pin=****]";}
    }
    public static final class PinRejected extends RuntimeException { public PinRejected(String m){super(m);} }
}
