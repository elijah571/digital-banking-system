package org.elibanking.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdAutoConfiguration {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> requestIdServletFilter() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setFilter(new CorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    private static final class CorrelationFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String incoming = request.getHeader(RequestIdContext.HEADER);
            if (incoming != null && !valid(incoming)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"X-Request-Id must contain 1 to 100 visible ASCII characters\"}");
                return;
            }
            String id = incoming == null ? UUID.randomUUID().toString() : incoming;
            response.setHeader(RequestIdContext.HEADER, id);
            HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                @Override public String getHeader(String name) {
                    return RequestIdContext.HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name);
                }
                @Override public Enumeration<String> getHeaders(String name) {
                    return RequestIdContext.HEADER.equalsIgnoreCase(name)
                            ? Collections.enumeration(Collections.singletonList(id)) : super.getHeaders(name);
                }
            };
            String previous = MDC.get("requestId");
            MDC.put("requestId", id);
            try {
                RequestIdContext.withId(id, () -> {
                    try { chain.doFilter(wrapped, response); }
                    catch (IOException | ServletException e) { throw new FilterFailure(e); }
                });
            } catch (FilterFailure failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                throw (ServletException) cause;
            } finally {
                if (previous == null) MDC.remove("requestId");
                else MDC.put("requestId", previous);
            }
        }

        private static boolean valid(String value) {
            return !value.isEmpty() && value.length() <= 100
                    && value.chars().allMatch(c -> c > 0x20 && c < 0x7f);
        }
    }

    private static final class FilterFailure extends RuntimeException {
        private FilterFailure(Exception cause) { super(cause); }
    }
}
