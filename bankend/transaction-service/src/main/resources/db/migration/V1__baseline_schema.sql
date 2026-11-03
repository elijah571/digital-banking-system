-- Baseline: the schema Hibernate (ddl-auto=update) had created before Flyway was introduced.
-- Existing databases are baselined at version 1 (spring.flyway.baseline-on-migrate), so this only runs on an empty database.
-- Never edit this file: add a new V{n}__*.sql instead.

CREATE TABLE `transactions` (
  `id` varchar(255) NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `receiver_account_number` varchar(255) NOT NULL,
  `reference_number` varchar(255) NOT NULL,
  `sender_account_number` varchar(255) NOT NULL,
  `status` enum('COMPLETED','FAILED','FLAGGED','PENDING','PENDING_VERIFICATION','PROCESSING') NOT NULL,
  `type` enum('DEPOSIT','PAYMENT','TRANSFER','WITHDRAWAL') NOT NULL,
  `sender_user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
