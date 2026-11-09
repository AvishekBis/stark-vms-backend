package com.starkgroup.vms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
import com.starkgroup.vms.document.DocumentType;
import com.starkgroup.vms.driver.LicenseNumber;
import com.starkgroup.vms.maintenance.MaintenanceType;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;
import com.starkgroup.vms.vehicle.FuelType;
import com.starkgroup.vms.vehicle.VehicleNumber;
import com.starkgroup.vms.vehicle.VehicleStatus;
import com.starkgroup.vms.vehicle.VehicleType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * NFR Performance (guideline 6): "List endpoints respond in under 500 ms for 1,000 vehicles". Seeds a realistic
 * fleet with JDBC batches - 1,000 vehicles, 300 drivers with 250 current and 400 closed assignments, 3,000
 * document records and 5,000 maintenance records - warms up, then times every list request through the full
 * MockMvc stack (JWT filter, controller, service, SQL incl. joins and subqueries, JSON) and asserts
 * p95 &lt; 500 ms. Results: docs/PERFORMANCE.md.
 *
 * <p>Runs in its own Spring context on its own embedded PostgreSQL behind a HikariCP pool, as in production.
 * The other API tests use zonky's {@code @AutoConfigureEmbeddedDatabase}, whose DataSource opens a new
 * PostgreSQL connection per transaction (about 100 ms on Windows) - fine for correctness, wrong for timing.
 * The separate database also means the seeded rows can never leak into other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListEndpointsPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(ListEndpointsPerformanceTest.class);

    private static final String API = "/api/v1";
    private static final int VEHICLE_COUNT = 1_000;
    private static final int DRIVER_COUNT = 300;
    private static final int CURRENT_ASSIGNMENTS = 250;
    private static final int CLOSED_ASSIGNMENTS = 400;
    private static final int DOCUMENTS_PER_VEHICLE = 3;
    private static final int MAINTENANCE_PER_VEHICLE = 5;
    /** JIT warm-up of the shared code path (filters, Hibernate, Jackson) before the first measurement. */
    private static final int GLOBAL_WARM_UP_RUNS = 50;
    private static final int WARM_UP_RUNS = 5;
    private static final int MEASURED_RUNS = 20;
    private static final long BUDGET_MS = 500;

    private static final String[] SERIES = {"GA", "GHA", "CHA", "BA", "TA", "NA", "LA", "HA"};
    private static final String[] CITIES = {"DHAKA METRO", "CHATTA METRO", "DHAKA"};
    private static final String[] BRANDS = {"Toyota", "Honda", "Nissan", "Mitsubishi", "Hino", "Tata", "Isuzu",
        "Ashok Leyland", "Mahindra", "Bajaj"};
    private static final String[] FIRST_NAMES = {"Rahim", "Karim", "Babul", "Jamal", "Kamal", "Sohel", "Rafiq",
        "Shafiq", "Nasir", "Habib", "Selim", "Alamgir", "Mizanur", "Anwar", "Delwar", "Faruk", "Harun", "Jashim",
        "Liton", "Monir"};
    private static final String[] LAST_NAMES = {"Uddin", "Hossain", "Akter", "Ahmed", "Islam", "Rahman", "Mia",
        "Sarker", "Khan", "Chowdhury", "Talukder", "Sheikh", "Molla", "Bhuiyan", "Mondal"};
    private static final VehicleType[] TYPES = VehicleType.values();
    private static final FuelType[] FUELS = FuelType.values();
    private static final DocumentType[] DOCUMENT_TYPES = DocumentType.values();
    private static final MaintenanceType[] MAINTENANCE_TYPES = MaintenanceType.values();

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final List<String> report = new ArrayList<>();

    private String token;

    @Autowired
    ListEndpointsPerformanceTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeAll
    void seedFleet() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = FleetApi.today();
        seedVehicles(now);
        seedDrivers(now, today);
        List<Long> vehicleIds = jdbcTemplate.queryForList("SELECT id FROM vehicles ORDER BY id", Long.class);
        List<Long> activeVehicleIds = jdbcTemplate.queryForList(
                "SELECT id FROM vehicles WHERE status = 'ACTIVE' ORDER BY id", Long.class);
        List<Long> activeDriverIds = jdbcTemplate.queryForList(
                "SELECT id FROM drivers WHERE status = 'ACTIVE' ORDER BY id", Long.class);
        seedAssignments(now, vehicleIds, activeVehicleIds, activeDriverIds);
        seedDocuments(today, vehicleIds);
        seedMaintenance(today, vehicleIds);
        // Fresh planner statistics, as autovacuum would provide in production.
        jdbcTemplate.execute("ANALYZE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Long.class)).isEqualTo(VEHICLE_COUNT);

        token = ApiTestSupport.staffToken(mockMvc);
        for (int i = 0; i < GLOBAL_WARM_UP_RUNS; i++) {
            perform(API + "/vehicles?page=" + (i % 50));
        }
    }

    @AfterAll
    void printReport() {
        log.info("List performance with {} vehicles, {} drivers, {} documents, {} maintenance records, "
                        + "{} measured runs each (ms):\n{}", VEHICLE_COUNT, DRIVER_COUNT,
                VEHICLE_COUNT * DOCUMENTS_PER_VEHICLE, VEHICLE_COUNT * MAINTENANCE_PER_VEHICLE, MEASURED_RUNS,
                String.join("\n", report));
    }

    Stream<Arguments> listRequests() {
        return Stream.of(
                request("vehicles: default list (page 0, size 20, createdAt desc)", "/vehicles",
                        json -> json.get("totalElements").asLong() == VEHICLE_COUNT
                                && json.get("content").size() == 20),
                request("vehicles: deep page 49 (rows 981-1000)", "/vehicles?page=49",
                        json -> json.get("page").asInt() == 49 && json.get("content").size() == 20),
                request("vehicles: max page size 100", "/vehicles?size=100",
                        json -> json.get("content").size() == 100),
                request("vehicles: search number, one hit", "/vehicles?search=-0500",
                        json -> json.get("totalElements").asLong() == 1),
                request("vehicles: search brand", "/vehicles?search=toyota",
                        json -> json.get("totalElements").asLong() == VEHICLE_COUNT / BRANDS.length),
                request("vehicles: search current driver name", "/vehicles?search=rahim",
                        json -> json.get("totalElements").asLong() > 0
                                && json.get("content").get(0).get("currentDriver").get("fullName").asText()
                                .startsWith("Rahim")),
                request("vehicles: status + type filter, sort manufactureYear desc",
                        "/vehicles?status=ACTIVE&type=CAR&sort=manufactureYear,desc",
                        json -> json.get("totalElements").asLong() > 0
                                && "CAR".equals(json.get("content").get(0).get("vehicleType").asText())),
                request("drivers: default list (fullName asc)", "/drivers",
                        json -> json.get("totalElements").asLong() == DRIVER_COUNT),
                request("drivers: search name", "/drivers?search=uddin",
                        json -> json.get("totalElements").asLong() == DRIVER_COUNT / LAST_NAMES.length),
                request("drivers: available=true (assign dialog)", "/drivers?available=true",
                        json -> json.get("totalElements").asLong() > 0),
                request("documents: expiring within 30 days", "/documents/expiring",
                        json -> json.get("totalElements").asLong() > 0),
                request("documents: expiring within 365 days, page 50", "/documents/expiring?withinDays=365&page=50",
                        json -> json.get("content").size() == 20),
                request("dashboard: summary", "/dashboard/summary",
                        json -> json.get("vehicles").get("total").asLong() == VEHICLE_COUNT
                                && json.get("drivers").get("assigned").asLong() == CURRENT_ASSIGNMENTS));
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
        String fullUri = uri.startsWith(API) ? uri : API + uri;
        MvcResult result = mockMvc.perform(get(fullUri).header(HttpHeaders.AUTHORIZATION, token)).andReturn();
        assertThat(result.getResponse().getStatus()).as("status of GET %s", fullUri).isEqualTo(200);
        return result;
    }

    private static Arguments request(String name, String uri, Predicate<JsonNode> sanityCheck) {
        return Arguments.of(name, uri, sanityCheck);
    }

    private void seedVehicles(Instant now) {
        List<Object[]> rows = new ArrayList<>(VEHICLE_COUNT);
        for (int i = 1; i <= VEHICLE_COUNT; i++) {
            String number = vehicleNumber(i);
            Timestamp createdAt = Timestamp.from(now.minus(i, ChronoUnit.MINUTES));
            rows.add(new Object[] {
                number, VehicleNumber.normalize(number), TYPES[i % TYPES.length].name(),
                BRANDS[i % BRANDS.length], "Model " + (i % 37), (short) (2005 + i % 21), "White",
                FUELS[i % FUELS.length].name(), vehicleStatusOf(i).name(), createdAt, createdAt, "perf-seed"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO vehicles (vehicle_number, number_key, vehicle_type, brand, model, "
                + "manufacture_year, color, fuel_type, status, created_at, updated_at, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    /** 300 unique fake names (20 first x 15 last names); every 30th driver INACTIVE; licences spread over time. */
    private void seedDrivers(Instant now, LocalDate today) {
        List<Object[]> rows = new ArrayList<>(DRIVER_COUNT);
        for (int i = 0; i < DRIVER_COUNT; i++) {
            String fullName = FIRST_NAMES[i % FIRST_NAMES.length] + " " + LAST_NAMES[i / FIRST_NAMES.length];
            String license = String.format(Locale.ROOT, "DK-%07d L%05d", 1_000_000 + i * 37, i);
            Timestamp createdAt = Timestamp.from(now.minus(i, ChronoUnit.HOURS));
            rows.add(new Object[] {
                fullName, String.format(Locale.ROOT, "017%08d", 10_000_000 + i), license,
                LicenseNumber.normalize(license), Date.valueOf(today.plusDays(-60 + (i * 7L) % 900)),
                i % 30 == 29 ? "INACTIVE" : "ACTIVE", createdAt, createdAt, "perf-seed"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO drivers (full_name, phone, license_number, license_key, license_expiry, "
                + "status, created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    /** 250 current assignments (ACTIVE vehicle + ACTIVE driver, BR-05/BR-06) and 400 closed history rows. */
    private void seedAssignments(Instant now, List<Long> vehicleIds, List<Long> activeVehicleIds,
                                 List<Long> activeDriverIds) {
        List<Object[]> rows = new ArrayList<>(CURRENT_ASSIGNMENTS + CLOSED_ASSIGNMENTS);
        for (int i = 0; i < CLOSED_ASSIGNMENTS; i++) {
            Instant assignedAt = now.minus(400L + i, ChronoUnit.DAYS);
            rows.add(new Object[] {
                vehicleIds.get((i * 7) % vehicleIds.size()), activeDriverIds.get(i % activeDriverIds.size()),
                Timestamp.from(assignedAt), Timestamp.from(assignedAt.plus(90, ChronoUnit.DAYS)), "perf-seed"});
        }
        for (int i = 0; i < CURRENT_ASSIGNMENTS; i++) {
            rows.add(new Object[] {
                activeVehicleIds.get(i * 3), activeDriverIds.get(i),
                Timestamp.from(now.minus(1L + i % 300, ChronoUnit.DAYS)), null, "perf-seed"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO vehicle_driver_assignments (vehicle_id, driver_id, assigned_at, "
                + "unassigned_at, created_by) VALUES (?, ?, ?, ?, ?)", rows);
    }

    /** Metadata only (no file bytes): 3 per vehicle, expiry dates from 60 days ago to about 13 months ahead. */
    private void seedDocuments(LocalDate today, List<Long> vehicleIds) {
        List<Object[]> rows = new ArrayList<>(vehicleIds.size() * DOCUMENTS_PER_VEHICLE);
        for (int i = 0; i < vehicleIds.size(); i++) {
            for (int k = 0; k < DOCUMENTS_PER_VEHICLE; k++) {
                LocalDate expiry = today.plusDays(-60 + (i * 13L + k * 101L) % 460);
                rows.add(new Object[] {
                    vehicleIds.get(i), DOCUMENT_TYPES[k % DOCUMENT_TYPES.length].name(), "DOC-" + i + "-" + k,
                    Date.valueOf(expiry.minusYears(1)), Date.valueOf(expiry), UUID.randomUUID().toString(),
                    "document-" + i + "-" + k + ".pdf", "application/pdf", 120_000L + i});
            }
        }
        jdbcTemplate.batchUpdate("INSERT INTO vehicle_documents (vehicle_id, document_type, document_number, "
                + "issue_date, expiry_date, storage_key, file_name, content_type, file_size, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'perf-seed')", rows);
    }

    /** 5 per vehicle over the last 12 months. */
    private void seedMaintenance(LocalDate today, List<Long> vehicleIds) {
        List<Object[]> rows = new ArrayList<>(vehicleIds.size() * MAINTENANCE_PER_VEHICLE);
        for (int i = 0; i < vehicleIds.size(); i++) {
            for (int k = 0; k < MAINTENANCE_PER_VEHICLE; k++) {
                rows.add(new Object[] {
                    vehicleIds.get(i), Date.valueOf(today.minusDays((i * 7L + k * 37L) % 365)),
                    MAINTENANCE_TYPES[(i + k) % MAINTENANCE_TYPES.length].name(),
                    BigDecimal.valueOf(1_500 + (i * 31L + k * 977L) % 40_000, 0), "Workshop " + (i % 12),
                    10_000 + i * 50 + k * 3_000, "Seeded record"});
            }
        }
        jdbcTemplate.batchUpdate("INSERT INTO maintenance_records (vehicle_id, service_date, maintenance_type, cost, "
                + "vendor_name, odometer_km, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'perf-seed')", rows);
    }

    /** E.g. "CHATTA METRO-GHA 12-0001": realistic Bangladeshi plates, unique by the running number. */
    private static String vehicleNumber(int i) {
        return String.format(Locale.ROOT, "%s-%s %02d-%04d", CITIES[i % CITIES.length], SERIES[i % SERIES.length],
                11 + i % 50, i);
    }

    private static VehicleStatus vehicleStatusOf(int i) {
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
