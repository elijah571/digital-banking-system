package org.elibanking.accountservice.idempotency;

/** The response stored for a DONE idempotency record. */
public record StoredResponse(Integer status, String bodyJson) {
}
