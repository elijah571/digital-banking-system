package org.elibanking.authservice;

import jakarta.persistence.EntityManager;
import org.elibanking.authservice.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET,
        "auth.admin-email=",
        "auth.admin-password="
})
@Import(TestcontainersConfiguration.class)
class AuthServiceApplicationTests {

	@Autowired UserAdminService users;
	@Autowired JdbcTemplate jdbc;
	@Autowired SessionFactory sessionFactory;
	@Autowired EntityManager entityManager;

	@Test
	void contextLoads() {
	}

	@Test
	@Transactional
	void userListAndDetailUseOneJpaQueryRegardlessOfResultSize() {
		String firstId = null;
		String emailDomain = "query-count-" + UUID.randomUUID() + ".example.test";
		for (int i = 0; i < 25; i++) {
			String id = UUID.randomUUID().toString();
			if (firstId == null) firstId = id;
			LocalDateTime now = LocalDateTime.now();
			jdbc.update("""
					INSERT INTO users (id, created_at, email, email_verified, enabled, full_name, password_hash, role, updated_at)
					VALUES (?, ?, ?, b'1', b'1', 'Query Count', 'not-used-by-this-test', 'USER', ?)
					""", id, now, id + "@" + emailDomain, now);
		}

		var statistics = sessionFactory.getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		entityManager.clear();
		assertEquals(25, users.listUsers().stream().filter(u -> u.email().contains(emailDomain)).count());
		assertEquals(1, statistics.getPrepareStatementCount(), "user list should use one query");

		statistics.clear();
		entityManager.clear();
		users.getUser(firstId);
		assertEquals(1, statistics.getPrepareStatementCount(), "user detail should use one query");
	}

}
