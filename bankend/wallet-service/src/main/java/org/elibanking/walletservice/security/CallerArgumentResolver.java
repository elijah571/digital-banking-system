package org.elibanking.walletservice.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Lets controllers declare a {@link Caller} parameter. The caller comes from the verified token, so a client cannot forge
 * identity with headers. Every route needs a token (SecurityConfig), so a request without one never gets here.
 */
@Configuration
public class CallerArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Caller.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String role = jwt.getToken().getClaimAsString("role");
            if ("SERVICE".equals(role)) {
                return Caller.internal();
            }
            return new Caller(jwt.getToken().getSubject(), jwt.getToken().getClaimAsString("email"),
                    jwt.getToken().getClaimAsString("name"), role);
        }
        // Fail closed. Headers are not identity, and no other authentication reaches a controller.
        throw new AccessDeniedException("no verified token");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
