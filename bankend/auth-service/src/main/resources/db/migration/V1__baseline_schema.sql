-- Baseline: the schema Hibernate (ddl-auto=update) had created before Flyway was introduced.
-- Existing databases are baselined at version 1 (spring.flyway.baseline-on-migrate), so this only runs on an empty database.
-- Never edit this file: add a new V{n}__*.sql instead.

CREATE TABLE `users` (
  `id` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `email` varchar(255) NOT NULL,
  `email_verified` bit(1) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `full_name` varchar(255) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` enum('ADMIN','USER') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `auth_tokens` (
  `id` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  `type` enum('EMAIL_VERIFICATION','PASSWORD_RESET','REFRESH') NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK3wqaabxbp24ko89s7lkjxch2v` (`token_hash`),
  KEY `idx_auth_tokens_user_type` (`user_id`,`type`),
  CONSTRAINT `FKkhs4tpy3l5krnk87ykkmafeic` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
