-- Preserve legacy role values while allowing the seven explicit operations roles.
-- Legacy ADMIN users receive ROLE_SUPER_ADMIN permissions when their token is issued.
ALTER TABLE users MODIFY COLUMN role ENUM(
    'USER',
    'ADMIN',
    'ROLE_SUPER_ADMIN',
    'ROLE_COMPLIANCE_OFFICER',
    'ROLE_TREASURY_APPROVER',
    'ROLE_FINANCE_RECON_ADMIN',
    'ROLE_RISK_ANALYST',
    'ROLE_CUSTOMER_SUPPORT',
    'ROLE_AUDITOR'
) NOT NULL;
