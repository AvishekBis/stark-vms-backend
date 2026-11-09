package com.starkgroup.vms.vehicle;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starkgroup.vms.support.ApiTestSupport;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import jakarta.servlet.RequestDispatcher;

/**
 * Vehicle API against a real (embedded) PostgreSQL with the Flyway schema: FR-V01..FR-V05, TC-01..TC-07, TC-11.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class VehicleApiTest {

    private static final String VEHICLES = "/api/v1/vehicles";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String staffToken;

    @Autowired
    VehicleApiTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM vehicles");
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
    }

    @Test
    void tc01_createWithValidDataReturns201WithLocationAndAppearsInList() throws Exception {
        MvcResult result = mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("DHAKA METRO-GA 11-2345").toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/vehicles/\\d+$")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.vehicleNumber").value("DHAKA METRO-GA 11-2345"))
                .andExpect(jsonPath("$.vehicleType").value("CAR"))
                .andExpect(jsonPath("$.manufactureYear").value(2019))
                .andExpect(jsonPath("$.fuelType").value("OCTANE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.createdBy").value(ApiTestSupport.STAFF_EMAIL))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.numberKey").doesNotExist())
                .andReturn();
        long id = ApiTestSupport.readJson(result).get("id").asLong();

        mockMvc.perform(get(VEHICLES).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mockMvc.perform(get(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleNumber").value("DHAKA METRO-GA 11-2345"));
    }

    @Test
    void tc01_optionalFieldsMayBeOmittedAndAreReturnedAsNull() throws Exception {
        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleNumber\":\"DHK-5\",\"vehicleType\":\"BUS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brand").value(nullValue()))
                .andExpect(jsonPath("$.manufactureYear").value(nullValue()))
                .andExpect(jsonPath("$.fuelType").value(nullValue()));
    }

    @Test
    void tc02_createWithExistingNumberReturns409WithFieldError() throws Exception {
        createVehicle("DHK-1234");

        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("DHK-1234").toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/duplicate-vehicle-number"))
                .andExpect(jsonPath("$.title").value("Duplicate vehicle number"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Vehicle number DHK-1234 is already registered."))
                .andExpect(jsonPath("$.instance").value(VEHICLES))
                .andExpect(jsonPath("$.errors[0].field").value("vehicleNumber"))
                .andExpect(jsonPath("$.errors[0].message").value("Vehicle number DHK-1234 is already registered."));
    }

    @Test
    void tc03_createWithDifferentlyFormattedExistingNumberReturns409() throws Exception {
        createVehicle("DHK-1234");

        for (String variant : new String[] {"dhk 1234", " DHK1234 ", "Dhk-12 34"}) {
            mockMvc.perform(post(VEHICLES)
                            .header(HttpHeaders.AUTHORIZATION, staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleJson(variant).toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://stark-vms/errors/duplicate-vehicle-number"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Integer.class)).isEqualTo(1);
    }

    @Test
    void br01_databaseUniqueConstraintIsTheSafetyNet() throws Exception {
        createVehicle("DHK-1234");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO vehicles (vehicle_number, number_key, vehicle_type) VALUES (?, ?, ?)",
                        "dhk 1234", "DHK1234", "CAR"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_vehicles_number_key");
    }

    @Test
    void tc04_editWithoutChangingNumberReturns200AndPersistsChanges() throws Exception {
        JsonNode created = createVehicle("DHK-1234");
        long id = created.get("id").asLong();
        ObjectNode changes = vehicleJson("DHK-1234").put("model", "Premio").put("color", "Black")
                .put("version", created.get("version").asLong());

        mockMvc.perform(put(VEHICLES + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changes.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleNumber").value("DHK-1234"))
                .andExpect(jsonPath("$.model").value("Premio"))
                .andExpect(jsonPath("$.color").value("Black"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()))
                .andExpect(jsonPath("$.updatedAt").value(not(created.get("updatedAt").asText())));

        mockMvc.perform(get(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.model").value("Premio"));
    }

    @Test
    void br02_editToAnotherVehiclesNumberReturns409() throws Exception {
        createVehicle("DHK-1234");
        long otherId = createVehicle("CTG-9999").get("id").asLong();

        mockMvc.perform(put(VEHICLES + "/" + otherId)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("dhk-1234").put("version", 0).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("vehicleNumber"));
    }

    @Test
    void editOfMissingVehicleReturns404() throws Exception {
        mockMvc.perform(put(VEHICLES + "/999999")
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("DHK-1").put("version", 0).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/not-found"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void tc05_missingVehicleNumberReturns400WithFieldError() throws Exception {
        ObjectNode body = vehicleJson("ignored");
        body.remove("vehicleNumber");

        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("vehicleNumber"))
                .andExpect(jsonPath("$.errors[0].message").value("Vehicle number is required."));
    }

    @Test
    void br03_invalidFieldsAreReportedPerField() throws Exception {
        ObjectNode body = vehicleJson("---").put("manufactureYear", 1975).put("brand", "x".repeat(51));
        body.remove("vehicleType");

        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("vehicleNumber", "vehicleType", "manufactureYear", "brand")));
    }

    @Test
    void br03_manufactureYearAfterNextYearIsRejected() throws Exception {
        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("DHK-1").put("manufactureYear", 3000).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("manufactureYear"))
                .andExpect(jsonPath("$.errors[0].message", containsString("between 1980 and")));
    }

    @Test
    void unknownEnumValueReturns400ForThatField() throws Exception {
        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("DHK-1").put("vehicleType", "SPACESHIP").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("vehicleType"))
                .andExpect(jsonPath("$.errors[0].message", containsString("CAR")));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/bad-request"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void tc06_callsWithoutTokenReturn401ProblemDetails() throws Exception {
        mockMvc.perform(get(VEHICLES))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value(VEHICLES));
        mockMvc.perform(post(VEHICLES).contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("DHK-1").toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(VEHICLES + "/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tc07_staffCannotDeleteVehicle() throws Exception {
        long id = createVehicle("DHK-1234").get("id").asLong();

        mockMvc.perform(delete(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/forbidden"))
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(get(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk());
    }

    @Test
    void tc07_adminDeletesVehicle() throws Exception {
        long id = createVehicle("DHK-1234").get("id").asLong();

        mockMvc.perform(delete(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void tc11_pageSize500IsCappedAt100() throws Exception {
        createVehicle("DHK-1");

        mockMvc.perform(get(VEHICLES).param("size", "500").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listIsPagedAndSortedOnTheServer() throws Exception {
        createVehicle("CCC-3", 2015);
        createVehicle("AAA-1", 2022);
        createVehicle("BBB-2", 2018);

        mockMvc.perform(get(VEHICLES).param("sort", "vehicleNumber,asc").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].vehicleNumber", contains("AAA-1", "BBB-2")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get(VEHICLES).param("sort", "manufactureYear,desc").param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[*].vehicleNumber", contains("CCC-3")));
        mockMvc.perform(get(VEHICLES).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[*].vehicleNumber", contains("BBB-2", "AAA-1", "CCC-3")));
    }

    @Test
    void unknownSortFieldReturns400() throws Exception {
        mockMvc.perform(get(VEHICLES).param("sort", "color,asc").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/bad-request"))
                .andExpect(jsonPath("$.detail", containsString("Unknown sort field 'color'")));
    }

    @Test
    void invalidPagingParametersReturn400() throws Exception {
        mockMvc.perform(get(VEHICLES).param("page", "-1").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        mockMvc.perform(get(VEHICLES).param("size", "abc").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void pageBeyondTheDatabaseOffsetLimitReturns400NotA500() throws Exception {
        mockMvc.perform(get(VEHICLES).param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/bad-request"));
    }

    @Test
    void frameworkErrorsUseTheContractProblemTypes() throws Exception {
        mockMvc.perform(get("/api/v1/unknown").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/unknown"));
        mockMvc.perform(post(VEHICLES).header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.TEXT_PLAIN).content("DHK-1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/bad-request"));
    }

    @Test
    void errorsOutsideSpringMvcAreProblemDetailsWithoutInternals() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1//vehicles")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("internal detail")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/bad-request"))
                .andExpect(jsonPath("$.instance").value("/api/v1//vehicles"))
                .andExpect(jsonPath("$.detail").value("The request could not be processed."))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        mockMvc.perform(get("/error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/not-found"));
    }

    @Test
    void searchMatchesVehicleNumberCaseInsensitivelyAndIgnoringFormatting() throws Exception {
        createVehicle("DHK-1234");
        createVehicle("DHAKA METRO-GA 11-2345");
        createVehicle("CTG-9999");

        assertSearch("dhk", "DHK-1234");
        assertSearch("dhk1234", "DHK-1234");
        assertSearch("metro-ga 11", "DHAKA METRO-GA 11-2345");
        assertSearch("ga112345", "DHAKA METRO-GA 11-2345");
        mockMvc.perform(get(VEHICLES).param("search", "%").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void corsPreflightAllowsConfiguredOriginAndExposesLocation() throws Exception {
        mockMvc.perform(options(VEHICLES)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Location")));
        mockMvc.perform(options(VEHICLES)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    private void assertSearch(String term, String expectedNumber) throws Exception {
        mockMvc.perform(get(VEHICLES).param("search", term).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].vehicleNumber", contains(expectedNumber)));
    }

    private JsonNode createVehicle(String number) throws Exception {
        return createVehicle(number, 2019);
    }

    private JsonNode createVehicle(String number, int year) throws Exception {
        MvcResult result = mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson(number).put("manufactureYear", year).toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return ApiTestSupport.readJson(result);
    }

    private ObjectNode vehicleJson(String number) {
        return objectMapper.createObjectNode()
                .put("vehicleNumber", number)
                .put("vehicleType", "CAR")
                .put("brand", "Toyota")
                .put("model", "Axio")
                .put("manufactureYear", 2019)
                .put("color", "White")
                .put("fuelType", "OCTANE");
    }
}
