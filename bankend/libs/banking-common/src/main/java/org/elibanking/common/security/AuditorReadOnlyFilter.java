package org.elibanking.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Defense in depth: auditor credentials are read-only even when a service is called without the gateway. */
public final class AuditorReadOnlyFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaimAsStringList("roles") != null
                && jwt.getToken().getClaimAsStringList("roles").contains("ROLE_AUDITOR")
                && !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Auditor access is read-only\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
