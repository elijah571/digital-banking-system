package org.elibanking.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/** Provides the validated inbound request ID to synchronous downstream HTTP calls. */
public final class RequestIdContext {

    public static final String HEADER = "X-Request-Id";
    private static final int MAX_LENGTH = 100;

    private RequestIdContext() {
    }

    public static Optional<String> current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        String requestId = attributes.getRequest().getHeader(HEADER);
        if (!isValid(requestId)) {
            return Optional.empty();
        }
        return Optional.of(requestId);
    }

    public static void apply(HttpHeaders headers) {
        current().ifPresent(requestId -> headers.set(HEADER, requestId));
    }

    private static boolean isValid(String requestId) {
        return requestId != null && !requestId.isEmpty() && requestId.length() <= MAX_LENGTH
                && requestId.chars().allMatch(character -> character > 0x20 && character < 0x7f);
    }
}
