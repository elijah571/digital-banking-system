package org.elibanking.walletservice.security;

/**
 * Who is calling, from the verified token. A user's token gives the user id, email and role. A service token gives
 * {@link #internal()}, where every field is null. Headers are never identity: the legacy X-User-* path was removed in
 * phase 3 (wallet-plan 8.18).
 */
public record Caller(String userId, String email, String fullName, String role, java.util.Set<String> authorities) {

    public Caller(String userId, String email, String fullName, String role) {
        this(userId, email, fullName, role, "ADMIN".equals(role)
                ? org.elibanking.common.security.AdminRbac.allPermissions() : java.util.Set.of());
    }

    /** A service-to-service call, with no user: ownership checks are skipped. */
    public static Caller internal() {
        return new Caller(null, null, null, null, java.util.Set.of());
    }

    public boolean isAdmin() {
        return hasPermission(org.elibanking.common.security.AdminRbac.PAYOUTS_APPROVE);
    }

    public boolean isInternal() {
        return userId == null;
    }

    /** True if this caller may read/use a resource owned by {@code ownerId}. */
    public boolean canAccess(String ownerId) {
        return isInternal() || userId.equals(ownerId)
                || hasPermission(org.elibanking.common.security.AdminRbac.PAYOUTS_READ);
    }

    public boolean hasPermission(String permission) {
        return authorities.contains(permission) || authorities.contains("ROLE_SUPER_ADMIN");
    }
}
