-- The outbound pipeline (wallet-plan 8.3): one table for both types, WITHDRAWAL and EXTERNAL_TRANSFER. The state set is closed by a
-- CHECK, and the daily counter is the same for both types, so external transfers use up withdrawal allowance (8.3, D21).
CREATE TABLE outbound_payout (
  id                         BIGINT        NOT NULL AUTO_INCREMENT,
  type                       VARCHAR(20)   NOT NULL,
  user_id                    VARCHAR(100)  NOT NULL,
  account_number             VARCHAR(20)   NOT NULL,            -- the account the money leaves
  amount                     DECIMAL(19,2) NOT NULL,
  fee                        DECIMAL(19,2) NOT NULL DEFAULT 0,  -- OUTBOUND_FEE, flat NGN, 0 by default (8.3)
  currency                   VARCHAR(3)    NOT NULL,
  bank_code                  VARCHAR(20)   NOT NULL,
  destination_account_number VARCHAR(20)   NOT NULL,
  destination_account_name   VARCHAR(200)  NULL,                -- from the name enquiry, when there is one
  narration                  VARCHAR(255)  NULL,
  state                      VARCHAR(20)   NOT NULL,
  failure_reason             VARCHAR(255)  NULL,
  provider_reference         VARCHAR(100)  NULL,
  version                    BIGINT        NOT NULL DEFAULT 0,
  created_at                 DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at                 DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT ck_payout_type CHECK (type IN ('WITHDRAWAL', 'EXTERNAL_TRANSFER')),
  CONSTRAINT ck_payout_state CHECK (state IN ('REQUESTED', 'PENDING_APPROVAL', 'APPROVED', 'PROCESSING', 'COMPLETED',
                                              'FAILED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REVERSED')),
  CONSTRAINT ck_payout_amount CHECK (amount > 0),
  CONSTRAINT ck_payout_fee CHECK (fee >= 0),
  KEY ix_payout_state_updated (state, updated_at),
  KEY ix_payout_account (account_number, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The money sent from one account on one day, for WITHDRAWAL_DAILY_MAX (8.3). Incremented when a payout is opened, and given back
-- by the transitions that end it without a settle (fix 5, D21).
CREATE TABLE outbound_daily_usage (
  account_number VARCHAR(20)   NOT NULL,
  usage_day      DATE          NOT NULL,
  total          DECIMAL(19,2) NOT NULL DEFAULT 0,
  version        BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (account_number, usage_day),
  CONSTRAINT ck_usage_total CHECK (total >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
