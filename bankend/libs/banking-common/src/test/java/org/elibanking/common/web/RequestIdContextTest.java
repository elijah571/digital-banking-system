package org.elibanking.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdContextTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void appliesAValidInboundRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER, "gateway-request-42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        HttpHeaders headers = new HttpHeaders();
        RequestIdContext.apply(headers);

        assertEquals("gateway-request-42", headers.getFirst(RequestIdContext.HEADER));
    }

    @Test
    void doesNotForwardAnInvalidInboundRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER, "bad\r\nrequest");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertTrue(RequestIdContext.current().isEmpty());
    }

    @Test
    void hasNoRequestIdOutsideAnHttpRequest() {
        assertTrue(RequestIdContext.current().isEmpty());
    }
}
