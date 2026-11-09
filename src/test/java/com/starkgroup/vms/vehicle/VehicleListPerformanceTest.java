package com.starkgroup.vms.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.starkgroup.vms.support.ApiTestSupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * NFR Performance (guideline 6): "List endpoints respond in under 500 ms for 1,000 vehicles". Seeds 1,000
 * vehicles with one JDBC batch, warms up, then times each list request through the full MockMvc stack (JWT
 * filter, controller, service, SQL, JSON) and asserts p95 &lt; 500 ms. Results: docs/PERFORMANCE.md.
 *
 * <p>Runs in its own Spring context on its own embedded PostgreSQL behind a HikariCP pool, as in production.
 * The other API tests use zonky's {@code @AutoConfigureEmbeddedDatabase}, whose DataSource opens a new
 * PostgreSQL connection per transaction (about 100 ms on Windows) - fine for correctness, wrong for timing.
 * The separate database also means the 1,000 rows can never leak into other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleListPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(VehicleListPerformanceTest.class);

    private static final String VEHICLES = "/api/v1/vehicles";
    private static final int VEHICLE_COUNT = 1_000;
    /** JIT warm-up of the shared code path (filters, Hibernate, Jackson) before the first measurement. */
    private static final int GLOBAL_WARM_UP_RUNS = 50;
    private static final int WARM_UP_RUNS = 5;
    private static final int MEASURED_RUNS = 20;
    private static final long BUDGET_MS = 500;

    private static final String[] SERIES = {"GA", "GHA", "CHA", "BA", "TA", "NA", "LA", "HA"};
    private static final String[] CITIES = {"DHAKA METRO", "CHATTA METRO", "DHAKA"};
    private static final String[] BRANDS = {"Toyota", "Honda", "Nissan", "Mitsubishi", "Hino", "Tata", "Isuzu",
        "Ashok Leyland", "Mahindra", "Bajaj"};
    private static final VehicleType[] TYPES = VehicleType.values();
    private static final FuelType[] FUELS = FuelType.values();

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final List<String> report = new ArrayList<>();

    private String token;

    @Autowired
    VehicleListPerformanceTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeAll
    void seedThousandVehicles() throws Exception {
        Instant newest = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Object[]> rows = new ArrayList<>(VEHICLE_COUNT);
        for (int i = 1; i <= VEHICLE_COUNT; i++) {
            String number = vehicleNumber(i);
            Timestamp createdAt = Timestamp.from(newest.minus(i, ChronoUnit.MINUTES));
            rows.add(new Object[] {
                number, VehicleNumber.normalize(number), TYPES[i % TYPES.length].name(),
                BRANDS[i % BRANDS.length], "Model " + (i % 37), (short) (2005 + i % 21), "White",
                FUELS[i % FUELS.length].name(), statusOf(i).name(),
                createdAt, createdAt, "perf-seed"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO vehicles (vehicle_number, number_key, vehicle_type, brand, model, "
                + "manufacture_year, color, fuel_type, status, created_at, updated_at, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        // Fresh planner statistics, as autovacuum would provide in production.
        jdbcTemplate.execute("ANALYZE vehicles");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Long.class)).isEqualTo(VEHICLE_COUNT);
        token = ApiTestSupport.staffToken(mockMvc);
        for (int i = 0; i < GLOBAL_WARM_UP_RUNS; i++) {
            perform(VEHICLES + "?page=" + (i % 50));
        }
    }

    @AfterAll
    void printReport() {
        log.info("List performance with {} vehicles, {} measured runs each (ms):\n{}", VEHICLE_COUNT,
                MEASURED_RUNS, String.join("\n", report));
    }

    Stream<Arguments> listRequests() {
        return Stream.of(
                request("default list (page 0, size 20, createdAt desc)", VEHICLES,
                        json -> json.get("totalElements").asLong() == VEHICLE_COUNT
                                && json.get("content").size() == 20),
                request("deep page 49 (rows 981-1000)", VEHICLES + "?page=49",
                        json -> json.get("page").asInt() == 49 && json.get("content").size() == 20),
                request("max page size 100", VEHICLES + "?size=100",
                        json -> json.get("content").size() == 100),
                request("search number, one hit (-0500)", VEHICLES + "?search=-0500",
                        json -> json.get("totalElements").asLong() == 1),
                request("search number, normalized (dhakametroga)", VEHICLES + "?search=dhakametroga",
                        json -> json.get("totalElements").asLong() > 0),
                request("search number, broad (METRO)", VEHICLES + "?search=METRO",
                        json -> json.get("totalElements").asLong() > 500),
                request("sort manufactureYear desc", VEHICLES + "?sort=manufactureYear,desc",
                        json -> json.get("content").get(0).get("manufactureYear").asInt() == 2025),
                request("sort vehicleNumber asc, page 25", VEHICLES + "?sort=vehicleNumber,asc&page=25",
                        json -> json.get("content").size() == 20));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listRequests")
    void listRespondsWithinBudget(String name, String uri, Predicate<JsonNode> sanityCheck) throws Exception {
        assertThat(sanityCheck).as("response content of %s", uri).accepts(ApiTestSupport.readJson(perform(uri)));
        for (int i = 0; i < WARM_UP_RUNS; i++) {
            perform(uri);
        }
        double[] millis = new double[MEASURED_RUNS];
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start = System.nanoTime();
            perform(uri);
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        double median = (millis[MEASURED_RUNS / 2 - 1] + millis[MEASURED_RUNS / 2]) / 2;
        double p95 = millis[(int) Math.ceil(0.95 * MEASURED_RUNS) - 1];
        report.add(String.format(Locale.ROOT, "| %s | `GET %s` | %.1f | %.1f | %.1f |", name, uri, median, p95,
                millis[MEASURED_RUNS - 1]));
        assertThat(p95).as("p95 of GET %s in ms", uri).isLessThan(BUDGET_MS);
    }

    private MvcResult perform(String uri) throws Exception {
        MvcResult result = mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, token)).andReturn();
        assertThat(result.getResponse().getStatus()).as("status of GET %s", uri).isEqualTo(200);
        return result;
    }

    private static Arguments request(String name, String uri, Predicate<JsonNode> sanityCheck) {
        return Arguments.of(name, uri, sanityCheck);
    }

    /** E.g. "CHATTA METRO-GHA 12-0001": realistic Bangladeshi plates, unique by the running number. */
    static String vehicleNumber(int i) {
        return String.format(Locale.ROOT, "%s-%s %02d-%04d", CITIES[i % CITIES.length], SERIES[i % SERIES.length],
                11 + i % 50, i);
    }

    private static VehicleStatus statusOf(int i) {
        if (i % 10 == 0) {
            return VehicleStatus.INACTIVE;
        }
        return i % 10 == 1 ? VehicleStatus.MAINTENANCE : VehicleStatus.ACTIVE;
    }

    /** Real PostgreSQL on a free port with a pooled DataSource (pool size 5 = production default DB_POOL_SIZE). */
    @TestConfiguration(proxyBeanMethods = false)
    static class PooledEmbeddedPostgres {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres performancePostgres() throws IOException {
            return EmbeddedPostgres.builder().start();
        }

        @Bean(destroyMethod = "close")
        DataSource dataSource(EmbeddedPostgres performancePostgres) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(performancePostgres.getJdbcUrl("postgres", "postgres"));
            config.setUsername("postgres");
            config.setMaximumPoolSize(5);
            config.setPoolName("performance-test");
            return new HikariDataSource(config);
        }
    }
}
