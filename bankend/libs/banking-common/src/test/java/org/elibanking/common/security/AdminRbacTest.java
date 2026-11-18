package org.elibanking.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRbacTest {

    @Test
    void legacyAdminRetainsSuperAdminPermissions() {
        assertEquals(AdminRbac.allPermissions(), AdminRbac.permissionsFor("ADMIN"));
        assertEquals(AdminRbac.allPermissions(), AdminRbac.permissionsFor("ROLE_SUPER_ADMIN"));
    }

    @Test
    void eachStaffRoleHasOnlyItsCatalogPermissions() {
        Map<String, Set<String>> expected = Map.ofEntries(
                Map.entry("ROLE_COMPLIANCE_OFFICER", Set.of(AdminRbac.CUSTOMERS_READ, AdminRbac.ACCOUNTS_READ,
                        AdminRbac.ACCOUNTS_FREEZE, AdminRbac.KYC_READ, AdminRbac.KYC_REVIEW,
                        AdminRbac.TRANSACTIONS_READ, AdminRbac.CRYPTO_TRANSACTIONS_READ,
                        AdminRbac.RISK_ALERTS_READ, AdminRbac.AUDIT_LOGS_READ)),
                Map.entry("ROLE_TREASURY_APPROVER", Set.of(AdminRbac.ACCOUNTS_READ, AdminRbac.TRANSACTIONS_READ,
                        AdminRbac.PAYOUTS_READ, AdminRbac.PAYOUTS_APPROVE, AdminRbac.CRYPTO_WALLETS_READ,
                        AdminRbac.CRYPTO_TRANSACTIONS_READ, AdminRbac.CRYPTO_OPERATIONS_APPROVE,
                        AdminRbac.LEDGER_READ, AdminRbac.RECONCILIATION_READ, AdminRbac.AUDIT_LOGS_READ)),
                Map.entry("ROLE_FINANCE_RECON_ADMIN", Set.of(AdminRbac.ACCOUNTS_READ, AdminRbac.TRANSACTIONS_READ,
                        AdminRbac.PAYOUTS_READ, AdminRbac.CRYPTO_TRANSACTIONS_READ, AdminRbac.LEDGER_READ,
                        AdminRbac.RECONCILIATION_READ, AdminRbac.RECONCILIATION_RESOLVE,
                        AdminRbac.PROVIDERS_WEBHOOKS_READ, AdminRbac.AUDIT_LOGS_READ)),
                Map.entry("ROLE_RISK_ANALYST", Set.of(AdminRbac.CUSTOMERS_READ, AdminRbac.ACCOUNTS_READ,
                        AdminRbac.ACCOUNTS_FREEZE, AdminRbac.TRANSACTIONS_READ, AdminRbac.PAYOUTS_READ,
                        AdminRbac.CRYPTO_TRANSACTIONS_READ, AdminRbac.RISK_ALERTS_READ,
                        AdminRbac.RISK_ALERTS_RESOLVE, AdminRbac.AUDIT_LOGS_READ)),
                Map.entry("ROLE_CUSTOMER_SUPPORT", Set.of(AdminRbac.CUSTOMERS_READ, AdminRbac.ACCOUNTS_READ,
                        AdminRbac.TRANSACTIONS_READ, AdminRbac.PAYOUTS_READ, AdminRbac.CRYPTO_WALLETS_READ,
                        AdminRbac.CRYPTO_TRANSACTIONS_READ)),
                Map.entry("ROLE_AUDITOR", Set.of(AdminRbac.CUSTOMERS_READ, AdminRbac.ACCOUNTS_READ,
                        AdminRbac.KYC_READ, AdminRbac.LEDGER_READ, AdminRbac.RECONCILIATION_READ,
                        AdminRbac.TRANSACTIONS_READ, AdminRbac.PAYOUTS_READ, AdminRbac.CRYPTO_WALLETS_READ,
                        AdminRbac.CRYPTO_TRANSACTIONS_READ, AdminRbac.RISK_ALERTS_READ, AdminRbac.AUDIT_LOGS_READ)));

        assertEquals(expected.keySet(), AdminRbac.roleNames());
        expected.forEach((role, permissions) -> assertEquals(permissions, AdminRbac.permissionsFor(role), role));
    }

    @Test
    void legacyAdminAndStaffClaimsResolveWithoutRoleEscalation() {
        AdminJwtAuthenticationConverter converter = new AdminJwtAuthenticationConverter();
        var legacyAdmin = converter.convert(Jwt.withTokenValue("legacy").header("alg", "HS256")
                .claim("role", "ADMIN").build());
        Set<String> legacyAuthorities = legacyAdmin.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(java.util.stream.Collectors.toSet());
        assertTrue(legacyAuthorities.contains("ROLE_SUPER_ADMIN"));
        assertTrue(legacyAuthorities.containsAll(AdminRbac.allPermissions()));

        var auditor = converter.convert(Jwt.withTokenValue("staff").header("alg", "HS256")
                .claim("role", "ADMIN").claim("roles", Set.of("ROLE_AUDITOR"))
                .claim("authorities", AdminRbac.permissionsFor("ROLE_AUDITOR")).build());
        Set<String> auditorAuthorities = auditor.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(java.util.stream.Collectors.toSet());
        assertTrue(auditorAuthorities.contains("ROLE_AUDITOR"));
        assertTrue(auditorAuthorities.containsAll(AdminRbac.permissionsFor("ROLE_AUDITOR")));
        assertFalse(auditorAuthorities.contains("ROLE_SUPER_ADMIN"));
        assertFalse(auditorAuthorities.contains(AdminRbac.PAYOUTS_APPROVE));
    }
}
