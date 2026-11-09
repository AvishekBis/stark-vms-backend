package com.starkgroup.vms.driver;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * FR-DR01 driver CRUD, unique phone / licence (service + DB constraint), search and availability.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class DriverApiTest {

    private static final String DRIVERS = "/api/v1/drivers";

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String staffToken;

    @Autowired
    DriverApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() throws Exception {
        ApiTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
    }

    @Test
    void frDr01_staffCreatesDriverWith201LocationAndNormalizedPhone() throws Exception {
        ObjectNode body = FleetApi.driverJson("  Rahim Uddin ", "+880 1711-000001", " DK0123456L00001 ");

        mockMvc.perform(post(DRIVERS).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/drivers/\\d+$")))
                .andExpect(jsonPath("$.fullName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.phone").value("01711000001"))
                .andExpect(jsonPath("$.licenseNumber").value("DK0123456L00001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.licenseStatus").value("VALID"))
                .andExpect(jsonPath("$.daysUntilLicenseExpiry").isNumber())
                .andExpect(jsonPath("$.currentVehicle").value(nullValue()))
                .andExpect(jsonPath("$.createdBy").value(ApiTestSupport.STAFF_EMAIL))
                .andExpect(jsonPath("$.licenseKey").doesNotExist());
    }

    @Test
    void frDr01_readUpdateAndAdminDelete() throws Exception {
        long id = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");
        ObjectNode changes = FleetApi.driverJson("Rahim Uddin Khan", "01711000001", "DK1")
                .put("licenseExpiry", FleetApi.today().minusDays(2).toString())
                .put("version", 0);

        mockMvc.perform(put(DRIVERS + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(changes.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Rahim Uddin Khan"))
                .andExpect(jsonPath("$.licenseStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.daysUntilLicenseExpiry").value(-2));
        mockMvc.perform(get(DRIVERS + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Rahim Uddin Khan"));

        mockMvc.perform(delete(DRIVERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(DRIVERS + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void frDr01_staffCannotDeleteDriver() throws Exception {
        long id = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");

        mockMvc.perform(delete(DRIVERS + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/forbidden"));
    }

    @Test
    void frDr01_duplicatePhoneIncludingFormattedVariantsReturns409() throws Exception {
        FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");

        for (String variant : new String[] {"01711000001", "01711-000001", "+8801711000001", "(017) 11 000 001"}) {
            mockMvc.perform(post(DRIVERS).header(HttpHeaders.AUTHORIZATION, staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FleetApi.driverJson("Other Person", variant, "DK-OTHER").toString()))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://stark-vms/errors/duplicate-driver-phone"))
                    .andExpect(jsonPath("$.detail")
                            .value("Phone number 01711000001 is already registered to another driver."))
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
        }
    }

    @Test
    void frDr01_duplicateLicenceIncludingNormalizedVariantsReturns409() throws Exception {
        FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK0123456L00001");

        for (String variant : new String[] {"DK0123456L00001", "dk0123456l00001", "DK-0123456 L00001"}) {
            mockMvc.perform(post(DRIVERS).header(HttpHeaders.AUTHORIZATION, staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FleetApi.driverJson("Other Person", "01811000002", variant).toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://stark-vms/errors/duplicate-driver-license"))
                    .andExpect(jsonPath("$.detail").value(
                            "Licence number " + variant + " is already registered to another driver."))
                    .andExpect(jsonPath("$.errors[0].field").value("licenseNumber"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM drivers", Integer.class)).isEqualTo(1);
    }

    @Test
    void frDr01_editingADriverKeepingOwnPhoneAndLicenceIsAllowed() throws Exception {
        long id = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");

        mockMvc.perform(put(DRIVERS + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FleetApi.driverJson("Rahim", "01711-000001", "dk1").put("version", 0).toString()))
                .andExpect(status().isOk());
    }

    @Test
    void frDr01_databaseConstraintsAreTheSafetyNet() throws Exception {
        FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO drivers (full_name, phone, license_number, "
                        + "license_key, license_expiry) VALUES ('X', '01711000001', 'Z', 'Z', CURRENT_DATE)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_drivers_phone");
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO drivers (full_name, phone, license_number, "
                        + "license_key, license_expiry) VALUES ('X', '01811000002', 'dk-1', 'DK1', CURRENT_DATE)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_drivers_license_key");
    }

    @Test
    void frDr01_validationErrorsArePerField() throws Exception {
        ObjectNode body = FleetApi.driverJson("", "12345", "x".repeat(41));
        body.remove("licenseExpiry");

        mockMvc.perform(post(DRIVERS).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("fullName", "phone", "licenseNumber", "licenseExpiry")));
    }

    @Test
    void frDr01_driverWithAssignmentHistoryCannotBeDeleted() throws Exception {
        long vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
        long driverId = FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK1");
        FleetApi.assign(mockMvc, staffToken, vehicleId, driverId);
        mockMvc.perform(delete("/api/v1/vehicles/" + vehicleId + "/driver")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(DRIVERS + "/" + driverId).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/driver-has-history"))
                .andExpect(jsonPath("$.title").value("Driver has history"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("INACTIVE")));
    }

    @Test
    void frDr01_searchOnNamePhoneAndLicence() throws Exception {
        FleetApi.createDriver(mockMvc, staffToken, "Rahim Uddin", "01711000001", "DK-0123456-L00001");
        FleetApi.createDriver(mockMvc, staffToken, "Karim Hossain", "01811000002", "CTG-777");

        assertDriverSearch("rahim", "Rahim Uddin");
        assertDriverSearch("HOSS", "Karim Hossain");
        assertDriverSearch("01811", "Karim Hossain");
        assertDriverSearch("+880 1711", "Rahim Uddin");
        assertDriverSearch("dk0123456", "Rahim Uddin");
        assertDriverSearch("ctg-777", "Karim Hossain");
    }

    @Test
    void frDr01_listIsSortedByNameByDefaultAndFiltersByStatus() throws Exception {
        FleetApi.createDriver(mockMvc, staffToken, FleetApi.driverJson("Zahid", "01711000001", "DK1")
                .put("status", "INACTIVE"));
        FleetApi.createDriver(mockMvc, staffToken, "Anis", "01811000002", "DK2");

        mockMvc.perform(get(DRIVERS).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullName", contains("Anis", "Zahid")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(DRIVERS).param("status", "INACTIVE").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[*].fullName", contains("Zahid")));
        mockMvc.perform(get(DRIVERS).param("sort", "fullName,desc").param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content[*].fullName", contains("Zahid", "Anis")));
        mockMvc.perform(get(DRIVERS).param("sort", "phone,asc").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(DRIVERS).param("status", "RETIRED").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void frDr02_availableListsOnlyActiveDriversWithoutCurrentVehicle() throws Exception {
        long vehicleId = FleetApi.createVehicle(mockMvc, staffToken, "DHK-1234");
        long busy = FleetApi.createDriver(mockMvc, staffToken, "Busy Driver", "01711000001", "DK1");
        FleetApi.createDriver(mockMvc, staffToken, "Free Driver", "01811000002", "DK2");
        FleetApi.createDriver(mockMvc, staffToken, FleetApi.driverJson("Inactive Driver", "01911000003", "DK3")
                .put("status", "INACTIVE"));
        FleetApi.assign(mockMvc, staffToken, vehicleId, busy);

        mockMvc.perform(get(DRIVERS).param("available", "true").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullName", contains("Free Driver")));
        mockMvc.perform(get(DRIVERS).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.totalElements").value(3));

        JsonNode busyDriver = ApiTestSupport.readJson(mockMvc.perform(get(DRIVERS + "/" + busy)
                .header(HttpHeaders.AUTHORIZATION, staffToken)).andReturn());
        assertThat(busyDriver.get("currentVehicle").get("id").asLong()).isEqualTo(vehicleId);
        assertThat(busyDriver.get("currentVehicle").get("vehicleNumber").asText()).isEqualTo("DHK-1234");
    }

    private void assertDriverSearch(String term, String expectedName) throws Exception {
        mockMvc.perform(get(DRIVERS).param("search", term).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullName", contains(expectedName)));
    }
}
