-- Baseline: the schema Hibernate (ddl-auto=update) had created before Flyway was introduced.
-- Existing databases are baselined at version 1 (spring.flyway.baseline-on-migrate), so this only runs on an empty database.
-- Never edit this file: add a new V{n}__*.sql instead.

CREATE TABLE `accounts` (
  `id` varchar(255) NOT NULL,
  `account_holder_name` varchar(255) NOT NULL,
  `account_number` varchar(255) NOT NULL,
  `account_status` enum('ACTIVE','BLOCKED','CLOSED') NOT NULL,
  `account_type` enum('CURRENT','FIXED_DEPOSIT','SAVINGS') NOT NULL,
  `balance` decimal(15,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `daily_transaction_limit` decimal(15,2) NOT NULL,
  `email` varchar(255) NOT NULL,
  `phone` varchar(255) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `owner_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6kplolsdtr3slnvx97xsy2kc8` (`account_number`),
  UNIQUE KEY `UK8rrji25uac5w2w1aslevt39a2` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
