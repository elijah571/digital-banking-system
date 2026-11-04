package org.elibanking.accountservice.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
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
 * Lets controllers declare a {@link Caller} parameter. With a verified token the caller comes from the token, so a
 * client cannot forge X-User-* headers. Only the legacy internal path (no token) still reads the headers.
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
            return new Caller(jwt.getToken().getSubject(), jwt.getToken().getClaimAsString("email"), role);
        }
        return new Caller(blankToNull(request.getHeader(Caller.USER_ID)),
                blankToNull(request.getHeader(Caller.USER_EMAIL)),
                blankToNull(request.getHeader(Caller.USER_ROLE)));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
