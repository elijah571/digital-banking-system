package org.elibanking.authservice.entity;

public enum Role {
    USER,
    /** Legacy seeded administrator; treated as ROLE_SUPER_ADMIN for compatibility. */
    ADMIN,
    ROLE_SUPER_ADMIN,
    ROLE_COMPLIANCE_OFFICER,
    ROLE_TREASURY_APPROVER,
    ROLE_FINANCE_RECON_ADMIN,
    ROLE_RISK_ANALYST,
    ROLE_CUSTOMER_SUPPORT,
    ROLE_AUDITOR;

    public boolean isStaff() {
        return this != USER;
    }

    public String staffRoleClaim() {
        return this == ADMIN ? "ROLE_SUPER_ADMIN" : (isStaff() ? name() : null);
    }
}
