package com.starkgroup.vms.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN (and optionally a STAFF) account at start-up when the configured
 * e-mail does not exist yet. Existing accounts are never modified.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final BootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(BootstrapProperties properties, UserRepository userRepository,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(properties.adminEmail())) {
            log.warn("No bootstrap admin configured (APP_ADMIN_EMAIL is empty); skipping account creation.");
        } else {
            createIfMissing(properties.adminEmail(), properties.adminPassword(), properties.adminFullName(), Role.ADMIN);
        }
        if (!isBlank(properties.staffEmail())) {
            createIfMissing(properties.staffEmail(), properties.staffPassword(), properties.staffFullName(), Role.STAFF);
        }
    }

    private void createIfMissing(String rawEmail, String password, String fullName, Role role) {
        String email = AuthService.normalizeEmail(rawEmail);
        if (userRepository.existsByEmail(email)) {
            return;
        }
        if (password == null) {
            throw new IllegalStateException("Bootstrap password for " + email + " is not set.");
        }
        PasswordPolicy.violation(password).ifPresent(rule -> {
            throw new IllegalStateException("Bootstrap password for " + email + ": " + rule);
        });
        userRepository.save(new User(email, passwordEncoder.encode(password), fullName.trim(), role));
        log.info("Created bootstrap {} account {}", role, email);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
