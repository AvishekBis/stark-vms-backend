package com.starkgroup.vms;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starkgroup.vms.maintenance.MaintenanceRecord;
import com.starkgroup.vms.maintenance.MaintenanceRecordRepository;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * ADR 0023 / contract 7.2 on vehicles, drivers and maintenance records against the real database: version in the
 * responses, required in PUT, 409 stale-version, server-side changes bump it, assignments do not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class OptimisticLockingApiTest {

    private static final String STALE = "https://stark-vms/errors/stale-version";

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final MaintenanceRecordRepository maintenanceRepository;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;

    private String adminToken;
    private String staffToken;

    @Autowired
    OptimisticLockingApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate,
                             MaintenanceRecordRepository maintenanceRepository,
                             TransactionTemplate transactionTemplate, DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.maintenanceRepository = maintenanceRepository;
        this.transactionTemplate = transactionTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void setUp() throws Exception {
        ApiTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
    }

    @Test
    void vehicleSecondSaveWithTheSameVersionIsStaleAndChangesNothing() throws Exception {
        JsonNode created = FleetApi.createVehicle(mockMvc, staffToken, FleetApi.vehicleJson("DHK-1234")
                .put("version", 99));
        long id = created.get("id").asLong();
        assertThat(created.get("version").asLong()).as("a version in POST is ignored").isZero();

        putJson("/api/v1/vehicles/" + id, FleetApi.vehicleJson("DHK-1234").put("model", "Premio").put("version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("Premio"))
                .andExpect(jsonPath("$.version").value(1));

        putJson("/api/v1/vehicles/" + id, FleetApi.vehicleJson("DHK-1234").put("model", "Allion").put("version", 0))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(STALE))
                .andExpect(jsonPath("$.title").value("Record changed"))
                .andExpect(jsonPath("$.detail").value(
                        "This vehicle was changed by someone else since you opened it. Reload and try again."))
                .andExpect(jsonPath("$.instance").value("/api/v1/vehicles/" + id))
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(get("/api/v1/vehicles/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.model").value("Premio"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[0].version").value(1));
    }

    /** Contract 7.2.1: a PUT that changes no value writes nothing, so the version stays and an open form stays valid. */
    @Test
    void putWithUnchangedValuesKeepsTheVersion() throws Exception {
        long id = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");

        for (int i = 0; i < 2; i++) {
            putJson("/api/v1/vehicles/" + id, FleetApi.vehicleJson("DHK-1234").put("version", 0))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(0));
        }
    }

    @Test
    void vehiclePutWithoutVersionIs400OnTheVersionField() throws Exception {
        long id = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");

        putJson("/api/v1/vehicles/" + id, FleetApi.vehicleJson("DHK-1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("version"))
                .andExpect(jsonPath("$.errors[0].message").value("Version is required."));
    }

    @Test
    void statusChangeBumpsTheVersionSoAnOlderEditFormGets409() throws Exception {
        long id = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");

        FleetApi.changeStatus(mockMvc, adminToken, id, "MAINTENANCE");

        mockMvc.perform(get("/api/v1/vehicles/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.version").value(1));
        putJson("/api/v1/vehicles/" + id, FleetApi.vehicleJson("DHK-1234").put("version", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(STALE));
    }

    @Test
    void assigningAndUnassigningADriverDoesNotChangeEitherVersion() throws Exception {
        long vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
        long driverId = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");

        FleetApi.assign(mockMvc, staffToken, vehicleId, driverId);
        mockMvc.perform(delete("/api/v1/vehicles/" + vehicleId + "/driver")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.version").value(0));
        mockMvc.perform(get("/api/v1/drivers/" + driverId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void driverStaleVersionIs409AndMissingVersionIs400() throws Exception {
        long id = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");

        putJson("/api/v1/drivers/" + id, FleetApi.driverJson("Rahim", "01711000001", "DK1").put("version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        putJson("/api/v1/drivers/" + id, FleetApi.driverJson("Rahim U", "01711000001", "DK1")
                .put("status", "INACTIVE").put("version", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(STALE))
                .andExpect(jsonPath("$.detail").value(
                        "This driver was changed by someone else since you opened it. Reload and try again."))
                .andExpect(jsonPath("$.errors").doesNotExist());
        putJson("/api/v1/drivers/" + id, FleetApi.driverJson("Rahim", "01711000001", "DK1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        mockMvc.perform(get("/api/v1/drivers/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void maintenanceStaleVersionIs409AndListRowsCarryTheVersion() throws Exception {
        long vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
        JsonNode record = FleetApi.addMaintenance(mockMvc, staffToken, vehicleId, FleetApi.today(), "3500.00");
        String path = "/api/v1/maintenance/" + record.get("id").asLong();
        assertThat(record.get("version").asLong()).isZero();

        putJson(path, FleetApi.maintenanceJson(FleetApi.today(), "4200.00").put("version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        putJson(path, FleetApi.maintenanceJson(FleetApi.today(), "1.00").put("version", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This maintenance record was changed by someone else since "
                        + "you opened it. Reload and try again."))
                .andExpect(jsonPath("$.errors").doesNotExist());
        putJson(path, FleetApi.maintenanceJson(FleetApi.today(), "1.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId + "/maintenance")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[0].version").value(1))
                .andExpect(jsonPath("$.content[0].cost").value(4200.0));
    }

    /**
     * The race the service-level check cannot see: another transaction commits between our read and our flush.
     * Hibernate's versioned UPDATE then matches no row; the exception is the one GlobalExceptionHandler maps to the
     * same 409 stale-version.
     */
    @Test
    void aConcurrentCommitBetweenReadAndFlushIsAnOptimisticLockingFailure() throws Exception {
        long vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
        long recordId = FleetApi.addMaintenance(mockMvc, staffToken, vehicleId, FleetApi.today(), "3500.00")
                .get("id").asLong();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            MaintenanceRecord loaded = maintenanceRepository.findWithVehicleById(recordId).orElseThrow();
            commitConcurrentChange(recordId);
            loaded.setCost(new BigDecimal("9999.00"));
            maintenanceRepository.saveAndFlush(loaded);
        }))
                .isInstanceOfSatisfying(ObjectOptimisticLockingFailureException.class,
                        ex -> assertThat(ex.getPersistentClassName()).isEqualTo(MaintenanceRecord.class.getName()));
        assertThat(jdbcTemplate.queryForObject("SELECT cost FROM maintenance_records WHERE id = ?", BigDecimal.class,
                recordId)).isEqualByComparingTo("1");
    }

    @Test
    void migrationsV2_1AndV7AddedTheVersionColumns() {
        for (String table : new String[] {"vehicles", "drivers", "maintenance_records", "users"}) {
            assertThat(jdbcTemplate.queryForObject("SELECT is_nullable || ':' || column_default "
                    + "FROM information_schema.columns WHERE table_name = ? AND column_name = 'version'",
                    String.class, table)).as(table).isEqualTo("NO:0");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'users' AND column_name = 'password_changed_at'", String.class))
                .isEqualTo("timestamp with time zone");
    }

    /** Another client's change, committed on its own connection (not part of the test's transaction). */
    private void commitConcurrentChange(long recordId) {
        try (Connection other = dataSource.getConnection();
             PreparedStatement update = other.prepareStatement(
                     "UPDATE maintenance_records SET cost = 1, version = version + 1 WHERE id = ?")) {
            other.setAutoCommit(true);
            update.setLong(1, recordId);
            update.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ResultActions putJson(String path, ObjectNode body) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, staffToken)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
}
