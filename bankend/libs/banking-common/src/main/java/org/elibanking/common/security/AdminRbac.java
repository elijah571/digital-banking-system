package org.elibanking.common.security;

import java.util.Map;
import java.util.Set;

/** Canonical staff-role permission catalog shared by auth-service and the banking APIs. */
public final class AdminRbac {
    public static final String ADMIN_USERS_READ = "admin.users.read";
    public static final String ADMIN_USERS_MANAGE = "admin.users.manage";
    public static final String CUSTOMERS_READ = "customers.read";
    public static final String CUSTOMERS_MANAGE = "customers.manage";
    public static final String ACCOUNTS_READ = "accounts.read";
    public static final String ACCOUNTS_FREEZE = "accounts.freeze";
    public static final String KYC_READ = "kyc.read";
    public static final String KYC_REVIEW = "kyc.review";
    public static final String LEDGER_READ = "ledger.read";
    public static final String RECONCILIATION_READ = "reconciliation.read";
    public static final String RECONCILIATION_RESOLVE = "reconciliation.resolve";
    public static final String TRANSACTIONS_READ = "transactions.read";
    public static final String PAYOUTS_READ = "payouts.read";
    public static final String PAYOUTS_APPROVE = "payouts.approve";
    public static final String LIMITS_MANAGE = "limits.manage";
    public static final String PROVIDERS_WEBHOOKS_READ = "providers.webhooks.read";
    public static final String CRYPTO_WALLETS_READ = "crypto.wallets.read";
    public static final String CRYPTO_TRANSACTIONS_READ = "crypto.transactions.read";
    public static final String CRYPTO_OPERATIONS_APPROVE = "crypto.operations.approve";
    public static final String RISK_ALERTS_READ = "risk.alerts.read";
    public static final String RISK_ALERTS_RESOLVE = "risk.alerts.resolve";
    public static final String AUDIT_LOGS_READ = "audit.logs.read";

    private static final Set<String> ALL = Set.of(ADMIN_USERS_READ, ADMIN_USERS_MANAGE, CUSTOMERS_READ,
            CUSTOMERS_MANAGE, ACCOUNTS_READ, ACCOUNTS_FREEZE, KYC_READ, KYC_REVIEW, LEDGER_READ,
            RECONCILIATION_READ, RECONCILIATION_RESOLVE, TRANSACTIONS_READ, PAYOUTS_READ, PAYOUTS_APPROVE,
            LIMITS_MANAGE, PROVIDERS_WEBHOOKS_READ, CRYPTO_WALLETS_READ, CRYPTO_TRANSACTIONS_READ,
            CRYPTO_OPERATIONS_APPROVE, RISK_ALERTS_READ, RISK_ALERTS_RESOLVE, AUDIT_LOGS_READ);

    private static final Map<String, Set<String>> PERMISSIONS = Map.ofEntries(
            Map.entry("ROLE_SUPER_ADMIN", ALL),
            Map.entry("ROLE_COMPLIANCE_OFFICER", Set.of(CUSTOMERS_READ, ACCOUNTS_READ, ACCOUNTS_FREEZE,
                    KYC_READ, KYC_REVIEW, TRANSACTIONS_READ, CRYPTO_TRANSACTIONS_READ, RISK_ALERTS_READ, AUDIT_LOGS_READ)),
            Map.entry("ROLE_TREASURY_APPROVER", Set.of(ACCOUNTS_READ, TRANSACTIONS_READ, PAYOUTS_READ,
                    PAYOUTS_APPROVE, CRYPTO_WALLETS_READ, CRYPTO_TRANSACTIONS_READ, CRYPTO_OPERATIONS_APPROVE,
                    LEDGER_READ, RECONCILIATION_READ, AUDIT_LOGS_READ)),
            Map.entry("ROLE_FINANCE_RECON_ADMIN", Set.of(ACCOUNTS_READ, TRANSACTIONS_READ, PAYOUTS_READ,
                    CRYPTO_TRANSACTIONS_READ, LEDGER_READ, RECONCILIATION_READ, RECONCILIATION_RESOLVE,
                    PROVIDERS_WEBHOOKS_READ, AUDIT_LOGS_READ)),
            Map.entry("ROLE_RISK_ANALYST", Set.of(CUSTOMERS_READ, ACCOUNTS_READ, ACCOUNTS_FREEZE,
                    TRANSACTIONS_READ, PAYOUTS_READ, CRYPTO_TRANSACTIONS_READ, RISK_ALERTS_READ,
                    RISK_ALERTS_RESOLVE, AUDIT_LOGS_READ)),
            Map.entry("ROLE_CUSTOMER_SUPPORT", Set.of(CUSTOMERS_READ, ACCOUNTS_READ, TRANSACTIONS_READ,
                    PAYOUTS_READ, CRYPTO_WALLETS_READ, CRYPTO_TRANSACTIONS_READ)),
            Map.entry("ROLE_AUDITOR", Set.of(CUSTOMERS_READ, ACCOUNTS_READ, KYC_READ, LEDGER_READ,
                    RECONCILIATION_READ, TRANSACTIONS_READ, PAYOUTS_READ, CRYPTO_WALLETS_READ,
                    CRYPTO_TRANSACTIONS_READ, RISK_ALERTS_READ, AUDIT_LOGS_READ))
    );

    private AdminRbac() {}

    public static Set<String> permissionsFor(String role) {
        if ("ADMIN".equals(role)) return ALL; // Existing ADMIN accounts retain super-admin access.
        return PERMISSIONS.getOrDefault(role, Set.of());
    }

    public static Set<String> allPermissions() {
        return ALL;
    }

    public static Set<String> roleNames() {
        return PERMISSIONS.keySet();
    }

    public static boolean isStaffRole(String role) {
        return "ADMIN".equals(role) || PERMISSIONS.containsKey(role);
    }
}
