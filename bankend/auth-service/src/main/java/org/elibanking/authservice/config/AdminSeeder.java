package org.elibanking.authservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.authservice.entity.Role;
import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** Creates the first ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD if no user with that email exists yet. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(props.adminEmail()) || isBlank(props.adminPassword())) {
            log.info("ADMIN_EMAIL / ADMIN_PASSWORD not set — no admin account seeded");
            return;
        }
        String email = props.adminEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return;
        }
        User admin = new User();
        admin.setFullName("Administrator");
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(props.adminPassword()));
        admin.setRole(Role.ADMIN);
        admin.setEmailVerified(true);
        userRepository.save(admin);
        log.info("Seeded admin account {}", email);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
