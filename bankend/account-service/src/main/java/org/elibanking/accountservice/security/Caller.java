package org.elibanking.accountservice.security;

/**
 * Who is calling. A user's token gives the user id, email and role. A service token and the legacy internal path give
 * {@link #internal()}, where every field is null. The X-User-* headers are used only on the legacy path, which is
 * dev-only and off by default (wallet-plan 8.18).
 */
public record Caller(String userId, String email, String role) {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLE = "X-User-Role";

    /** A service-to-service call, with no user: ownership checks are skipped. */
    public static Caller internal() {
        return new Caller(null, null, null);
    }

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
