package org.elibanking.paymentservice.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Optional;
import java.util.Set;

/**
 * Who may call which endpoint, decided by the token's {@code sub} (wallet-plan 3.7). A service token proves that its
 * caller holds INTERNAL_JWT_SECRET, but not which service the caller is. So each endpoint names the services it accepts,
 * and every other service is refused, with 403.
 *
 * <p>Service names are the {@code sub} claims that the callers mint (see {@code ServiceTokens} in banking-common).
 */
public final class CallerAllowList {

    public static final String TRANSACTION = "transaction-service";
    public static final String WALLET = "wallet-service";
    public static final String FRAUD = "fraud-detection-service";
    public static final String PAYMENT = "payment-service";

    private static final String SERVICE_ROLE = "SERVICE";

    private CallerAllowList() {
    }

    /** A service token whose subject is one of the named services. */
    public static AuthorizationManager<RequestAuthorizationContext> only(String... services) {
        Set<String> allowed = Set.of(services);
        return (authentication, context) -> new AuthorizationDecision(
                jwtOf(authentication.get())
                        .filter(jwt -> SERVICE_ROLE.equals(jwt.getClaimAsString("role")))
                        .map(jwt -> allowed.contains(jwt.getSubject()))
                        .orElse(false));
    }

    /** A user token, USER or ADMIN. A service token is not a user, even if it is authenticated. */
    public static AuthorizationManager<RequestAuthorizationContext> users() {
        return (authentication, context) -> new AuthorizationDecision(
                jwtOf(authentication.get())
                        .map(jwt -> !SERVICE_ROLE.equals(jwt.getClaimAsString("role")))
                        .orElse(false));
    }

    private static Optional<Jwt> jwtOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return Optional.of(jwt.getToken());
        }
        return Optional.empty();
    }
}
