package org.elibanking.frauddetectionservice.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Optional;
import java.util.Set;

/**
 * Who may call which endpoint, decided by the token's {@code sub} (wallet-plan 3.7). A service token proves that its caller holds
 * INTERNAL_JWT_SECRET, but not which service the caller is, so each endpoint names the services it accepts.
 */
public final class CallerAllowList {

    public static final String WALLET = "wallet-service";

    private static final String SERVICE_ROLE = "SERVICE";

    private CallerAllowList() {
    }

    /** A service token whose subject is one of the named services. Any other caller is refused. */
    public static AuthorizationManager<RequestAuthorizationContext> only(String... services) {
        Set<String> allowed = Set.of(services);
        return (authentication, context) -> new AuthorizationDecision(
                jwtOf(authentication.get())
                        .filter(jwt -> SERVICE_ROLE.equals(jwt.getClaimAsString("role")))
                        .map(jwt -> allowed.contains(jwt.getSubject()))
                        .orElse(false));
    }

    private static Optional<Jwt> jwtOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return Optional.of(jwt.getToken());
        }
        return Optional.empty();
    }
}
