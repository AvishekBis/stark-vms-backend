package com.starkgroup.vms;

import java.io.IOException;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Starts a real PostgreSQL server from bundled binaries on port {@value #PORT} and points the
 * application at it. Data lives in a temporary directory and is recreated (and re-seeded) on
 * every start. Local-only default values (JWT secret, demo accounts) come from
 * local-embedded.properties; real environment variables still win.
 */
@TestConfiguration(proxyBeanMethods = false)
@PropertySource("classpath:local-embedded.properties")
public class LocalEmbeddedPostgresConfig {

    static final int PORT = 5433;
    private static final String DATABASE = "postgres";
    private static final String USER = "postgres";

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().setPort(PORT).start();
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(EmbeddedPostgres embeddedPostgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(embeddedPostgres.getJdbcUrl(USER, DATABASE));
        config.setUsername(USER);
        config.setMaximumPoolSize(5);
        config.setPoolName("embedded-postgres");
        return new HikariDataSource(config);
    }
}
