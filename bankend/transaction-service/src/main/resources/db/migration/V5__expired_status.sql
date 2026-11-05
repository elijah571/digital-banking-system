-- An abandoned transfer ends as EXPIRED (wallet-plan 8.34). Expand-only: the new value is added at the end of the list.
ALTER TABLE transactions
  MODIFY status ENUM('COMPLETED','FAILED','FLAGGED','PENDING','PENDING_VERIFICATION','PROCESSING','EXPIRED') NOT NULL;
