-- Optimistic lock on transactions (wallet-plan section 4, slice 3c.1). Two writers that read the same version cannot both
-- save: the second one fails, so a verify race can no longer complete and refund the same transfer.
-- Expand-only: a new column with a default, so the version still running reads and writes the table unchanged.
ALTER TABLE `transactions` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0;
