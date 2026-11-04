package org.elibanking.paymentservice.security;

/**
 * Who is calling, from the verified user token (wallet-plan 3.7, D8): the subject is the user id, and the email and role
 * are claims. X-User-* headers are never identity. A request without a verified token never reaches a controller.
 */
public record Caller(String userId, String email, String role) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /** A call with no user. A verified token always has a subject, so this never comes from a user's request. */
    public boolean isInternal() {
        return userId == null;
    }

    /** True if this caller may read/use a resource owned by {@code ownerId}. */
    public boolean canAccess(String ownerId) {
        return isInternal() || isAdmin() || userId.equals(ownerId);
    }
}
