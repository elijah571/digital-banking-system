package org.elibanking.walletservice.exception;

import org.springframework.http.HttpStatusCode;

/** A request the wallet refuses, with the status the client can act on, such as 403 for another user's account. */
public class RequestRefusedException extends RuntimeException {

    private final HttpStatusCode status;

    public RequestRefusedException(HttpStatusCode status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }
}
