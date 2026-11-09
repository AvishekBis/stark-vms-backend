package com.starkgroup.vms.assignment;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * FR-DR02 driver-vehicle assignment against real PostgreSQL incl. the partial unique indexes (BR-05, BR-06, TC-10).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class AssignmentApiTest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private String adminToken;
    private String staffToken;
    private long vehicleId;
    private long otherVehicleId;
    private long rahim;
    private long karim;

    @Autowired
    AssignmentApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void setUp() throws Exception {
        ApiTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
        vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
        otherVehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-5678");
        rahim = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");
        karim = FleetApi.createDriver(mockMvc, staffToken, "Karim Hossain", "01811000002", "DK2");
    }

    @Test
    void frDr02_staffAssignsDriverAndVehicleShowsCurrentDriver() throws Exception {
        mockMvc.perform(put(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + rahim + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.vehicleNumber").value("DHK-1234"))
                .andExpect(jsonPath("$.driverId").value(rahim))
                .andExpect(jsonPath("$.driverName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.assignedAt").isString())
                .andExpect(jsonPath("$.unassignedAt").value(nullValue()))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.assignedBy").value(ApiTestSupport.STAFF_EMAIL));

        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.currentDriver.id").value(rahim))
                .andExpect(jsonPath("$.currentDriver.fullName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.currentDriver.phone").value("01711000001"));
        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, staffToken)
                        .param("sort", "vehicleNumber,asc"))
                .andExpect(jsonPath("$.content[0].currentDriver.fullName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.content[1].currentDriver").value(nullValue()));
    }

    @Test
    void frDr02_assigningTheSameDriverAgainIsIdempotent() throws Exception {
        long first = FleetApi.assign(mockMvc, staffToken, vehicleId, rahim).get("id").asLong();

        assertThat(FleetApi.assign(mockMvc, staffToken, vehicleId, rahim).get("id").asLong()).isEqualTo(first);
        assertThat(countAssignments()).isEqualTo(1);
    }

    @Test
    void br05_changingTheDriverClosesThePreviousAssignmentAndKeepsHistory() throws Exception {
        long first = FleetApi.assign(mockMvc, staffToken, vehicleId, rahim).get("id").asLong();
        JsonNode second = FleetApi.assign(mockMvc, staffToken, vehicleId, karim);

        assertThat(second.get("id").asLong()).isNotEqualTo(first);
        assertThat(second.get("driverName").asText()).isEqualTo("Karim Hossain");
        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId + "/assignments")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].driverName").value("Karim Hossain"))
                .andExpect(jsonPath("$.content[0].current").value(true))
                .andExpect(jsonPath("$.content[1].driverName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.content[1].current").value(false))
                .andExpect(jsonPath("$.content[1].unassignedAt").isString());
        mockMvc.perform(get("/api/v1/drivers/" + rahim + "/assignments").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].current").value(false));
        // Rahim is free again and can drive the other vehicle.
        FleetApi.assign(mockMvc, staffToken, otherVehicleId, rahim);
    }

    @Test
    void tc10_assigningADriverWhoDrivesAnotherVehicleReturns409() throws Exception {
        FleetApi.assign(mockMvc, staffToken, otherVehicleId, rahim);

        mockMvc.perform(put(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + rahim + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/driver-already-assigned"))
                .andExpect(jsonPath("$.detail").value(
                        "Driver Rahim Uddin is already assigned to vehicle DHK-5678. Unassign them first."))
                .andExpect(jsonPath("$.errors[0].field").value("driverId"));
        assertThat(countAssignments()).isEqualTo(1);
    }

    @Test
    void br06_partialUniqueIndexesAllowOnlyOneOpenAssignment() throws Exception {
        FleetApi.assign(mockMvc, staffToken, vehicleId, rahim);

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO vehicle_driver_assignments "
                        + "(vehicle_id, driver_id, assigned_at) VALUES (?, ?, now())", otherVehicleId, rahim))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_assignment_open_driver");
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO vehicle_driver_assignments "
                        + "(vehicle_id, driver_id, assigned_at) VALUES (?, ?, now())", vehicleId, karim))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_assignment_open_vehicle");
        // Closed rows do not count.
        jdbcTemplate.update("INSERT INTO vehicle_driver_assignments (vehicle_id, driver_id, assigned_at, "
                + "unassigned_at) VALUES (?, ?, now() - interval '2 days', now() - interval '1 day')", vehicleId, karim);
    }

    /**
     * BR-06 rule 5 under a real race: another transaction opens an assignment for the same driver but has not
     * committed yet, so the service-level check cannot see it. The request then blocks on the partial unique index
     * and, once the other transaction commits, must end in the same 409 - never a 500.
     */
    @Test
    void br06_lostRaceOnThePartialUniqueIndexIs409NotA500() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection concurrent = dataSource.getConnection()) {
            concurrent.setAutoCommit(false);
            try (PreparedStatement insert = concurrent.prepareStatement("INSERT INTO vehicle_driver_assignments "
                    + "(vehicle_id, driver_id, assigned_at) VALUES (?, ?, now())")) {
                insert.setLong(1, otherVehicleId);
                insert.setLong(2, rahim);
                insert.executeUpdate();
            }

            Future<MvcResult> pending = executor.submit(() -> mockMvc.perform(put("/api/v1/vehicles/" + vehicleId
                            + "/driver")
                            .header(HttpHeaders.AUTHORIZATION, staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"driverId\":" + rahim + "}"))
                    .andReturn());
            awaitABackendWaitingForALock();
            concurrent.commit();

            MvcResult result = pending.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            JsonNode problem = ApiTestSupport.readJson(result);
            assertThat(problem.get("type").asText()).isEqualTo("https://stark-vms/errors/driver-already-assigned");
            assertThat(problem.get("detail").asText()).isEqualTo(AssignmentService.CONCURRENT_ASSIGNMENT_MESSAGE);
            assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("driverId");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicle_driver_assignments "
                + "WHERE driver_id = ? AND unassigned_at IS NULL", Integer.class, rahim)).isEqualTo(1);
    }

    /**
     * BR-05 under a race: while another transaction is setting the vehicle INACTIVE (row updated, not committed),
     * an assignment must wait for it (row lock) and then be refused - not read the old ACTIVE status and leave an
     * INACTIVE vehicle with a current driver.
     */
    @Test
    void br05_assignmentWaitsForAConcurrentStatusChangeAndIsThenRefused() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection concurrent = dataSource.getConnection()) {
            concurrent.setAutoCommit(false);
            try (PreparedStatement update = concurrent.prepareStatement(
                    "UPDATE vehicles SET status = 'INACTIVE' WHERE id = ?")) {
                update.setLong(1, vehicleId);
                update.executeUpdate();
            }

            Future<MvcResult> pending = executor.submit(() -> mockMvc.perform(put("/api/v1/vehicles/" + vehicleId
                            + "/driver")
                            .header(HttpHeaders.AUTHORIZATION, staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"driverId\":" + rahim + "}"))
                    .andReturn());
            awaitABackendWaitingForALock();
            concurrent.commit();

            MvcResult result = pending.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(ApiTestSupport.readJson(result).get("type").asText())
                    .isEqualTo("https://stark-vms/errors/assignment-not-allowed");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicle_driver_assignments", Integer.class))
                .isZero();
    }

    private void awaitABackendWaitingForALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The assign request never blocked on the partial unique index.");
    }

    @Test
    void frDr02_unassignReturns204AndSecondUnassignIsNoCurrentDriver() throws Exception {
        FleetApi.assign(mockMvc, staffToken, vehicleId, rahim);

        mockMvc.perform(delete(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.currentDriver").value(nullValue()));
        mockMvc.perform(delete(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/no-current-driver"))
                .andExpect(jsonPath("$.detail").value("Vehicle DHK-1234 has no current driver."));
    }

    @Test
    void br05_vehicleThatIsNotActiveCannotGetADriver() throws Exception {
        FleetApi.changeStatus(mockMvc, adminToken, vehicleId, "INACTIVE");

        mockMvc.perform(put(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + rahim + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/assignment-not-allowed"))
                .andExpect(jsonPath("$.detail", containsString("Vehicle DHK-1234 is INACTIVE")));
    }

    @Test
    void br05_inactiveDriverCannotBeAssigned() throws Exception {
        long inactive = FleetApi.createDriver(mockMvc, staffToken,
                FleetApi.driverJson("Babul Akter", "01511000012", "DK12").put("status", "INACTIVE")).get("id").asLong();

        mockMvc.perform(put(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + inactive + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/assignment-not-allowed"))
                .andExpect(jsonPath("$.detail", containsString("Driver Babul Akter is INACTIVE")));
    }

    @Test
    void frV07_settingVehicleInactiveClosesItsAssignment() throws Exception {
        FleetApi.assign(mockMvc, staffToken, vehicleId, rahim);

        FleetApi.changeStatus(mockMvc, adminToken, vehicleId, "INACTIVE");

        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.currentDriver").value(nullValue()));
        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId + "/assignments")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[0].current").value(false));
    }

    @Test
    void frV07_maintenanceStatusKeepsTheDriver() throws Exception {
        FleetApi.assign(mockMvc, staffToken, vehicleId, rahim);

        FleetApi.changeStatus(mockMvc, adminToken, vehicleId, "MAINTENANCE");

        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.currentDriver.fullName").value("Rahim Uddin"));
    }

    @Test
    void frDr01_settingDriverInactiveClosesTheirAssignment() throws Exception {
        FleetApi.assign(mockMvc, staffToken, vehicleId, rahim);

        mockMvc.perform(put("/api/v1/drivers/" + rahim).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Assigning the driver did not change the driver's version (contract 7.2.4).
                        .content(FleetApi.driverJson("Rahim Uddin", "01711000001", "DK1")
                                .put("status", "INACTIVE").put("version", 0).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.currentVehicle").value(nullValue()));
        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.currentDriver").value(nullValue()));
    }

    @Test
    void missingDriverIdIs400AndUnknownIdsAre404() throws Exception {
        mockMvc.perform(put(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("driverId"));
        mockMvc.perform(put(driverPath(vehicleId)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":999999}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(driverPath(999999)).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + rahim + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/drivers/999999/assignments").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNotFound());
    }

    private static String driverPath(long vehicle) {
        return "/api/v1/vehicles/" + vehicle + "/driver";
    }

    private int countAssignments() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vehicle_driver_assignments", Integer.class);
    }
}
