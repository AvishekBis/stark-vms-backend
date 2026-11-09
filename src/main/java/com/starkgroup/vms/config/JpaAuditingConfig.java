package com.starkgroup.vms.config;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.starkgroup.vms.auth.AuthenticatedUser;

/**
 * Fills createdAt / updatedAt / createdBy (FR-X01) on every auditable entity.
 * Timestamps come from the injected {@link Clock}, truncated to PostgreSQL TIMESTAMPTZ precision
 * (microseconds) so a response right after a write equals what a later read returns. The auditor is the e-mail of the
 * authenticated user, or {@value #SYSTEM_AUDITOR} for start-up jobs.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    public static final String SYSTEM_AUDITOR = "system";

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                return Optional.of(user.email());
            }
            return Optional.of(SYSTEM_AUDITOR);
        };
    }
}
