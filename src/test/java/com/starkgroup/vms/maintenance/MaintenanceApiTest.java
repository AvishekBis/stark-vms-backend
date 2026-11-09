package com.starkgroup.vms.maintenance;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * FR-M01 / FR-M02 / BR-09 against real PostgreSQL (NUMERIC(12,2), CHECK cost >= 0).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class MaintenanceApiTest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String staffToken;
    private long vehicleId;

    @Autowired
    MaintenanceApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() throws Exception {
        ApiTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
        vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
    }

    @Test
    void frM01_createReadUpdateAndAdminDelete() throws Exception {
        LocalDate today = FleetApi.today();

        String location = mockMvc.perform(post(historyPath()).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FleetApi.maintenanceJson(today, "3500.00").toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/maintenance/\\d+$")))
                .andExpect(jsonPath("$.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.vehicleNumber").value("DHK-1234"))
                .andExpect(jsonPath("$.serviceDate").value(today.toString()))
                .andExpect(jsonPath("$.maintenanceType").value("OIL_CHANGE"))
                .andExpect(jsonPath("$.cost").value(3500.0))
                .andExpect(jsonPath("$.vendorName").value("Navana Service Centre"))
                .andExpect(jsonPath("$.odometerKm").value(45210))
                .andExpect(jsonPath("$.createdBy").value(ApiTestSupport.STAFF_EMAIL))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        String recordPath = location.substring(location.indexOf("/api/v1/"));

        ObjectNode changes = FleetApi.maintenanceJson(today.minusDays(1), "4200.75").put("maintenanceType", "TYRE")
                .put("version", 0);
        mockMvc.perform(put(recordPath).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(changes.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceType").value("TYRE"))
                .andExpect(jsonPath("$.cost").value(4200.75));
        mockMvc.perform(get(recordPath).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cost").value(4200.75));

        mockMvc.perform(delete(recordPath).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(recordPath).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(recordPath).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void frM02_historyIsNewestFirstWithTotalCostAcrossAllPages() throws Exception {
        LocalDate today = FleetApi.today();
        FleetApi.addMaintenance(mockMvc, staffToken, vehicleId, today.minusDays(60), "1000.00");
        FleetApi.addMaintenance(mockMvc, staffToken, vehicleId, today, "2500.50");
        FleetApi.addMaintenance(mockMvc, staffToken, vehicleId, today.minusDays(10), "9000.25");
        long otherVehicle = FleetApi.createVehicle(mockMvc, staffToken, "CTG-9999");
        FleetApi.addMaintenance(mockMvc, staffToken, otherVehicle, today, "777.00");

        mockMvc.perform(get(historyPath()).param("size", "2").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].cost", contains(2500.5, 9000.25)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.recordCount").value(3))
                .andExpect(jsonPath("$.totalCost").value(12500.75));
        mockMvc.perform(get(historyPath()).param("size", "2").param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[*].cost", contains(1000.0)))
                .andExpect(jsonPath("$.totalCost").value(12500.75));
    }

    @Test
    void frM02_vehicleWithoutRecordsHasZeroTotal() throws Exception {
        mockMvc.perform(get(historyPath()).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalCost").value(0));
        mockMvc.perform(get("/api/v1/vehicles/999999/maintenance").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void br09_serviceDateInTheFutureReturns400() throws Exception {
        mockMvc.perform(post(historyPath()).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FleetApi.maintenanceJson(FleetApi.today().plusDays(1), "100").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("serviceDate"))
                .andExpect(jsonPath("$.errors[0].message").value("Service date cannot be in the future."));
    }

    @Test
    void br09_negativeCostAndOtherInvalidFieldsReturn400() throws Exception {
        ObjectNode body = FleetApi.maintenanceJson(FleetApi.today(), "-1").put("odometerKm", -5)
                .put("vendorName", "x".repeat(101));
        body.remove("maintenanceType");

        mockMvc.perform(post(historyPath()).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("cost", "odometerKm", "vendorName", "maintenanceType")));
        mockMvc.perform(post(historyPath()).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FleetApi.maintenanceJson(FleetApi.today(), "10.123").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cost"));
    }

    @Test
    void br09_databaseRejectsNegativeCost() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO maintenance_records (vehicle_id, service_date, "
                + "maintenance_type, cost) VALUES (?, CURRENT_DATE, 'REPAIR', -1)", vehicleId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_maintenance_cost");
    }

    private String historyPath() {
        return "/api/v1/vehicles/" + vehicleId + "/maintenance";
    }
}
