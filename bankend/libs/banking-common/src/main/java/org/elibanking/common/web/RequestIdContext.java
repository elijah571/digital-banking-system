package org.elibanking.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.function.Supplier;

/** Provides the validated inbound request ID to synchronous downstream HTTP calls. */
public final class RequestIdContext {

    public static final String HEADER = "X-Request-Id";
    private static final int MAX_LENGTH = 100;
    private static final ThreadLocal<String> ASYNC_REQUEST_ID = new ThreadLocal<>();

    private RequestIdContext() {
    }

    public static Optional<String> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String requestId = attributes.getRequest().getHeader(HEADER);
            if (isValid(requestId)) return Optional.of(requestId);
        }
        String requestId = ASYNC_REQUEST_ID.get();
        if (!isValid(requestId)) {
            return Optional.empty();
        }
        return Optional.of(requestId);
    }

    /** Runs async message handling with its validated correlation id and restores any prior thread context afterwards. */
    public static void withId(String requestId, Runnable action) {
        String previous = ASYNC_REQUEST_ID.get();
        if (isValid(requestId)) ASYNC_REQUEST_ID.set(requestId);
        else ASYNC_REQUEST_ID.remove();
        try {
            action.run();
        } finally {
            if (previous == null) ASYNC_REQUEST_ID.remove();
            else ASYNC_REQUEST_ID.set(previous);
        }
    }

    public static <T> T withId(String requestId, Supplier<T> action) {
        String previous = ASYNC_REQUEST_ID.get();
        if (isValid(requestId)) ASYNC_REQUEST_ID.set(requestId);
        else ASYNC_REQUEST_ID.remove();
        try {
            return action.get();
        } finally {
            if (previous == null) ASYNC_REQUEST_ID.remove();
            else ASYNC_REQUEST_ID.set(previous);
        }
    }

    public static void apply(HttpHeaders headers) {
        current().ifPresent(requestId -> headers.set(HEADER, requestId));
    }

    private static boolean isValid(String requestId) {
        return requestId != null && !requestId.isEmpty() && requestId.length() <= MAX_LENGTH
                && requestId.chars().allMatch(character -> character > 0x20 && character < 0x7f);
    }
}
