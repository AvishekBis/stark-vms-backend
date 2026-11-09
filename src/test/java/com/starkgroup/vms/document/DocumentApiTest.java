package com.starkgroup.vms.document;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * FR-D01 / FR-D02 / BR-07 / BR-08 against real PostgreSQL with database storage. Dates are relative to today
 * (Asia/Dhaka), so TC-08 / TC-09 hold whatever day the tests run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class DocumentApiTest {

    private static final byte[] PDF = FileValidatorTest.PDF;
    private static final byte[] PNG = FileValidatorTest.PNG;

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String staffToken;
    private long vehicleId;

    @Autowired
    DocumentApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate) {
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
    void frD01_uploadPdfReturns201WithLocationAndMetadata() throws Exception {
        LocalDate today = FleetApi.today();

        mockMvc.perform(upload(new MockMultipartFile("file", "C:\\scans\\tax-token.pdf", "application/pdf", PDF))
                        .param("documentType", "TAX_TOKEN")
                        .param("documentNumber", "TT-2026-0099")
                        .param("issueDate", today.minusYears(1).toString())
                        .param("expiryDate", today.plusDays(10).toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/documents/\\d+$")))
                .andExpect(jsonPath("$.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.vehicleNumber").value("DHK-1234"))
                .andExpect(jsonPath("$.documentType").value("TAX_TOKEN"))
                .andExpect(jsonPath("$.documentNumber").value("TT-2026-0099"))
                .andExpect(jsonPath("$.expiryStatus").value("EXPIRING_SOON"))
                .andExpect(jsonPath("$.daysUntilExpiry").value(10))
                .andExpect(jsonPath("$.fileName").value("tax-token.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileSize").value(PDF.length))
                .andExpect(jsonPath("$.createdBy").value(ApiTestSupport.STAFF_EMAIL))
                .andExpect(jsonPath("$.storageKey").doesNotExist());

        String storageKey = jdbcTemplate.queryForObject("SELECT storage_key FROM vehicle_documents", String.class);
        assertThat(storageKey).matches("[0-9a-f\\-]{36}");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stored_files WHERE storage_key = ?",
                Integer.class, storageKey)).isEqualTo(1);
    }

    @Test
    void frD01_downloadReturnsTheBytesWithSafeHeaders() throws Exception {
        long documentId = uploadDocument("insurance.png", "image/png", PNG, FleetApi.today().plusDays(100));

        mockMvc.perform(get("/api/v1/documents/" + documentId + "/file").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"insurance.png\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mockMvc.perform(get("/api/v1/documents/" + documentId + "/file").param("download", "true")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")));
        mockMvc.perform(get("/api/v1/documents/" + documentId + "/file"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tc08_tc09_listShowsComputedExpiryStatusSoonestFirst() throws Exception {
        LocalDate today = FleetApi.today();
        uploadDocument("valid.pdf", "application/pdf", PDF, today.plusDays(31));
        uploadDocument("expired.pdf", "application/pdf", PDF, today.minusDays(1));
        uploadDocument("soon.pdf", "application/pdf", PDF, today.plusDays(10));
        uploadDocument("today.pdf", "application/pdf", PDF, today);

        mockMvc.perform(get("/api/v1/vehicles/" + vehicleId + "/documents").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].fileName", contains("expired.pdf", "today.pdf", "soon.pdf", "valid.pdf")))
                .andExpect(jsonPath("$[*].expiryStatus", contains("EXPIRED", "EXPIRING_SOON", "EXPIRING_SOON",
                        "VALID")))
                .andExpect(jsonPath("$[*].daysUntilExpiry", contains(-1, 0, 10, 31)));
    }

    @Test
    void frD02_expiringEndpointIncludesExpiredAndHonoursWithinDays() throws Exception {
        LocalDate today = FleetApi.today();
        uploadDocument("expired.pdf", "application/pdf", PDF, today.minusDays(40));
        uploadDocument("soon.pdf", "application/pdf", PDF, today.plusDays(20));
        uploadDocument("later.pdf", "application/pdf", PDF, today.plusDays(60));

        mockMvc.perform(get("/api/v1/documents/expiring").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].fileName", contains("expired.pdf", "soon.pdf")));
        mockMvc.perform(get("/api/v1/documents/expiring").param("withinDays", "90")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/v1/documents/expiring").param("withinDays", "0")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[*].fileName", contains("expired.pdf")));
        mockMvc.perform(get("/api/v1/documents/expiring").param("withinDays", "366")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("withinDays"));
    }

    @Test
    void br07_wrongFileTypeReturns400OnFileField() throws Exception {
        byte[] executable = {0x4D, 0x5A, (byte) 0x90, 0x00};

        mockMvc.perform(upload(new MockMultipartFile("file", "invoice.pdf", "application/pdf", executable))
                        .param("documentType", "OTHER")
                        .param("expiryDate", FleetApi.today().plusDays(5).toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("Only PDF, JPG or PNG files are allowed."));
        mockMvc.perform(upload(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .param("documentType", "OTHER")
                        .param("expiryDate", FleetApi.today().plusDays(5).toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stored_files", Integer.class)).isZero();
    }

    @Test
    void br07_fileOver5MbReturns413() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        mockMvc.perform(upload(new MockMultipartFile("file", "big.pdf", "application/pdf", big))
                        .param("documentType", "OTHER")
                        .param("expiryDate", FleetApi.today().plusDays(5).toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/file-too-large"))
                .andExpect(jsonPath("$.detail").value("The file is larger than 5 MB."));
    }

    @Test
    void br07_expiryBeforeIssueDateReturns400() throws Exception {
        LocalDate today = FleetApi.today();

        mockMvc.perform(upload(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                        .param("documentType", "INSURANCE")
                        .param("issueDate", today.toString())
                        .param("expiryDate", today.minusDays(1).toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expiryDate"))
                .andExpect(jsonPath("$.errors[0].message").value("Expiry date must be on or after the issue date."));
    }

    @Test
    void br07_missingPartsAreFieldErrors() throws Exception {
        mockMvc.perform(multipart("/api/v1/vehicles/" + vehicleId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[*].field",
                        org.hamcrest.Matchers.containsInAnyOrder("file", "documentType", "expiryDate")))
                .andExpect(jsonPath("$.detail").value("One or more fields are invalid."));
        mockMvc.perform(multipart("/api/v1/vehicles/" + vehicleId + "/documents")
                        .param("documentType", "INSURANCE")
                        .param("expiryDate", FleetApi.today().toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("A file is required."));
    }

    @Test
    void br07_badEnumOrDateValuesAreFieldErrorsWithoutInternals() throws Exception {
        mockMvc.perform(upload(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                        .param("documentType", "BOARDING_PASS")
                        .param("expiryDate", "31/12/2026")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        org.hamcrest.Matchers.containsInAnyOrder("documentType", "expiryDate")))
                .andExpect(jsonPath("$.errors[0].message").value("Invalid value or wrong data type."))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Failed to convert"))));
    }

    @Test
    void br07_databaseRejectsExpiryBeforeIssue() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO vehicle_documents (vehicle_id, document_type, "
                        + "issue_date, expiry_date, storage_key, file_name, content_type, file_size) "
                        + "VALUES (?, 'OTHER', DATE '2026-01-02', DATE '2026-01-01', 'k', 'f.pdf', 'application/pdf', 1)",
                vehicleId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_vehicle_documents_dates");
    }

    @Test
    void frD01_staffCannotDeleteAdminDeletesDocumentAndFile() throws Exception {
        long documentId = uploadDocument("a.pdf", "application/pdf", PDF, FleetApi.today().plusDays(100));

        mockMvc.perform(delete("/api/v1/documents/" + documentId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/documents/" + documentId).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/documents/" + documentId).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stored_files", Integer.class)).isZero();
    }

    @Test
    void frD01_getOneDocumentAndUnknownVehicle() throws Exception {
        long documentId = uploadDocument("a.pdf", "application/pdf", PDF, FleetApi.today().plusDays(100));

        mockMvc.perform(get("/api/v1/documents/" + documentId).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiryStatus").value("VALID"));
        mockMvc.perform(get("/api/v1/vehicles/999999/documents").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void corsExposesContentDisposition() throws Exception {
        mockMvc.perform(options("/api/v1/documents/1/file")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString("Content-Disposition")));
    }

    private MockMultipartHttpServletRequestBuilder upload(MockMultipartFile file) {
        return multipart("/api/v1/vehicles/" + vehicleId + "/documents").file(file);
    }

    private long uploadDocument(String name, String contentType, byte[] bytes, LocalDate expiry) throws Exception {
        JsonNode created = ApiTestSupport.readJson(mockMvc.perform(
                        upload(new MockMultipartFile("file", name, contentType, bytes))
                                .param("documentType", "INSURANCE")
                                .param("expiryDate", expiry.toString())
                                .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isCreated())
                .andReturn());
        return created.get("id").asLong();
    }
}
