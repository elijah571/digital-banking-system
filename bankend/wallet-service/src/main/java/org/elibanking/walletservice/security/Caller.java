package org.elibanking.walletservice.security;

/**
 * Who is calling, from the verified token. A user's token gives the user id, email and role. A service token gives
 * {@link #internal()}, where every field is null. Headers are never identity: the legacy X-User-* path was removed in
 * phase 3 (wallet-plan 8.18).
 */
public record Caller(String userId, String email, String fullName, String role) {

    /** A service-to-service call, with no user: ownership checks are skipped. */
    public static Caller internal() {
        return new Caller(null, null, null, null);
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
