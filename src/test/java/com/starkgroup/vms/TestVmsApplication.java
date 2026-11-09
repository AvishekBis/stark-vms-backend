package com.starkgroup.vms;

import org.springframework.boot.SpringApplication;

/**
 * Runs the full application locally on an embedded PostgreSQL - no installation or Docker needed:
 * {@code ./mvnw spring-boot:test-run}. Activates the "local" profile (demo data, localhost CORS).
 */
public class TestVmsApplication {

    public static void main(String[] args) {
        SpringApplication.from(VmsApplication::main)
                .with(LocalEmbeddedPostgresConfig.class)
                .withAdditionalProfiles("local")
                .run(args);
    }
}
