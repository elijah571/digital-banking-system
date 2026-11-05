-- Baseline: the schema Hibernate (ddl-auto=update) had created before Flyway was introduced.
-- Existing databases are baselined at version 1 (spring.flyway.baseline-on-migrate), so this only runs on an empty database.
-- Never edit this file: add a new V{n}__*.sql instead.

CREATE TABLE `payments` (
  `id` varchar(255) NOT NULL,
  `account_number` varchar(255) NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `currency` varchar(255) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `status` enum('COMPLETED','CREATED','FAILED','PENDING','REFUNDED') DEFAULT NULL,
  `stripe_charge_id` varchar(255) DEFAULT NULL,
  `stripe_payment_intent_id` varchar(255) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
