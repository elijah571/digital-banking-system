package org.elibanking.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Converts signed auth-service role and permission claims into authorities in each API service. */
public final class AdminJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> names = new LinkedHashSet<>();
        String legacyRole = jwt.getClaimAsString("role");
        if ("SERVICE".equals(legacyRole)) {
            names.add("ROLE_SERVICE");
        } else if ("USER".equals(legacyRole)) {
            names.add("ROLE_USER");
        }

        Collection<String> roles = strings(jwt.getClaims().get("roles"));
        names.addAll(roles);
        if ("ADMIN".equals(legacyRole) && roles.isEmpty()) {
            // Existing access tokens remain valid as super-admin tokens until expiry.
            names.add("ROLE_SUPER_ADMIN");
            names.addAll(AdminRbac.allPermissions());
        }
        names.addAll(strings(jwt.getClaims().get("authorities")));

        List<SimpleGrantedAuthority> authorities = new ArrayList<>(names.size());
        names.forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
        return new JwtAuthenticationToken(jwt, authorities);
    }

    private static Collection<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
