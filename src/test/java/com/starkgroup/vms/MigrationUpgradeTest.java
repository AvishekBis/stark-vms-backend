package com.starkgroup.vms;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * The live Render database is upgraded in place from v1.x (Assignment 1) to v2.x. v1.1 applied V1, V2 and
 * V2_1__add_vehicle_version.sql; this test replays exactly that state in a separate schema and checks that the
 * v2.x migrations then validate and apply on top of it (ADR 0023, note on migration numbering).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class MigrationUpgradeTest {

    private static final String SCHEMA = "v1_upgrade_check";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void aDatabaseMigratedByV1UpgradesToTheCurrentSchema() {
        Flyway v1 = flyway().target("2.1").load();
        v1.migrate();
        assertThat(appliedVersions(v1)).containsExactly("1", "2", "2.1");

        Flyway current = flyway().load();
        current.migrate(); // validates the applied V1, V2, V2_1 checksums first (validateOnMigrate), as on Render

        List<String> applied = appliedVersions(current);
        assertThat(applied).startsWith("1", "2", "2.1", "3").endsWith("8");
        current.validate();
        for (String table : new String[] {"vehicles", "drivers", "maintenance_records", "users"}) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = ? AND table_name = ? AND column_name = 'version'",
                    Integer.class, SCHEMA, table)).as(table).isEqualTo(1);
        }
    }

    private FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull) // the "create schema" marker row has no version
                .map(Object::toString)
                .toList();
    }
}
