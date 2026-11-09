package com.starkgroup.vms.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean Validation's "now" ({@code @PastOrPresent}, {@code @Future} ...) is the application {@link Clock}
 * (Asia/Dhaka), not the server's default time zone.
 */
@Configuration
public class ValidationConfig {

    @Bean
    public ValidationConfigurationCustomizer clockProviderCustomizer(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
