package org.elibanking.paymentservice.security;

/**
 * Who is calling, taken from the X-User-* headers that the API gateway sets from the verified JWT
 * (the gateway strips any client-supplied copies). All fields are null for internal service-to-service
 * calls (e.g. transaction-service → account-service), which do not go through the gateway.
 */
public record Caller(String userId, String email, String role) {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLE = "X-User-Role";

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isInternal() {
        return userId == null;
    }

    /** True if this caller may read/use a resource owned by {@code ownerId}. */
    public boolean canAccess(String ownerId) {
        return isInternal() || isAdmin() || userId.equals(ownerId);
    }
}
