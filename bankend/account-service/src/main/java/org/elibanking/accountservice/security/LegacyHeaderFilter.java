package org.elibanking.accountservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The legacy internal path (wallet-plan 8.18, 8.19). It exists only for the old callers, and only while
 * {@code ACCOUNT_LEGACY_HEADERS_ENABLED=true}. Off by default, and removed in phase 3.
 *
 * <p>It covers exactly {@code PUT …/deduct}, {@code PUT …/credit}, {@code GET …/balance} and
 * {@code GET /{accountNumber}}, for 10-digit account numbers. A request on these paths with no {@code Authorization}
 * header is let through with the {@code LEGACY_INTERNAL} authority, and a warning is logged with the caller. Any other
 * request, and any request that carries a token, is handled by the normal token checks.
 *
 * <p>Not a Spring bean on purpose, so Boot does not also register it as a servlet filter. The security chain adds it.
 */
public final class LegacyHeaderFilter extends OncePerRequestFilter {

    public static final String LEGACY_ROLE = "LEGACY_INTERNAL";

    private static final Logger log = LoggerFactory.getLogger(LegacyHeaderFilter.class);
    private static final Pattern LEGACY_PATH = Pattern.compile("/api/v1/accounts/[0-9]{10}(/balance|/deduct|/credit)?");

    private final boolean enabled;

    public LegacyHeaderFilter(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled && request.getHeader("Authorization") == null && isLegacy(request)) {
            SecurityContextHolder.getContext().setAuthentication(new PreAuthenticatedAuthenticationToken(
                    "legacy-internal", null, List.of(new SimpleGrantedAuthority("ROLE_" + LEGACY_ROLE))));
            String userId = request.getHeader(Caller.USER_ID);
            log.warn("legacy header access: {} {} from {} (user-agent: {}, X-User-Id: {})",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                    request.getHeader("User-Agent"), userId == null ? "none" : userId);
        }
        chain.doFilter(request, response);
    }

    /** True for the four old endpoints, with the method each one uses. */
    static boolean isLegacy(HttpServletRequest request) {
        var matcher = LEGACY_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            return false;
        }
        String method = request.getMethod();
        String suffix = matcher.group(1);
        if (suffix == null || "/balance".equals(suffix)) {
            return "GET".equals(method);
        }
        return "PUT".equals(method);
    }
}
