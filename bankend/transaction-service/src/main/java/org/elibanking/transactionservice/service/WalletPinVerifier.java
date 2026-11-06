package org.elibanking.transactionservice.service;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.transactionservice.exception.PinLockedException;
import org.elibanking.transactionservice.exception.PinUnavailableException;
import org.elibanking.transactionservice.exception.TransactionAccessDeniedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Checks a transfer PIN with wallet-service (wallet-plan 3.5, D2, D-P8). The call carries transaction-service's SERVICE token, and
 * wallet counts a wrong PIN toward the lockout itself. Answers map to transfer errors:
 * <ul>
 *   <li>200: the PIN is right;</li>
 *   <li>403 "Wrong PIN" or "Set a PIN first": the transfer is refused;</li>
 *   <li>423: the PIN is locked, with Retry-After;</li>
 *   <li>anything else, including wallet being down: the transfer is refused, with 503 (fail closed).</li>
 * </ul>
 */
@Component
public class WalletPinVerifier implements PinVerifier {

    private static final long DEFAULT_RETRY_SECONDS = 30 * 60;

    private final RestClient wallet;
    private final ServiceTokens tokens;

    public WalletPinVerifier(RestClient.Builder builder,
                             @Value("${wallet-service.url}") String walletUrl,
                             @Qualifier("walletServiceTokens") ServiceTokens tokens) {
        this.wallet = builder.baseUrl(walletUrl).build();
        this.tokens = tokens;
    }

    @Override
    public void check(String userId, String pin) {
        try {
            wallet.post()
                    .uri("/internal/v1/pin/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .body(Map.of("userId", userId, "pin", pin))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 423) {
                throw new PinLockedException(retryAfterSeconds(e));
            }
            if (e.getStatusCode().value() == 403) {
                String body = e.getResponseBodyAsString();
                throw new TransactionAccessDeniedException(body.contains("Set a PIN first") ? "Set a PIN first" : "Wrong PIN");
            }
            throw new PinUnavailableException();
        } catch (RestClientException e) {
            // Wallet is down, slow, or sent a server error: the PIN cannot be checked, so the transfer does not go.
            throw new PinUnavailableException();
        }
    }

    private static long retryAfterSeconds(HttpClientErrorException e) {
        String value = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return value == null ? DEFAULT_RETRY_SECONDS : Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_RETRY_SECONDS;
        }
    }
}
