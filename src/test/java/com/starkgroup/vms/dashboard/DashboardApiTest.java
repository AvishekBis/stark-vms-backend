package com.starkgroup.vms.dashboard;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.starkgroup.vms.assignment.VehicleDriverAssignmentRepository;
import com.starkgroup.vms.dev.DemoDataSeeder;
import com.starkgroup.vms.document.VehicleDocumentRepository;
import com.starkgroup.vms.document.storage.StorageService;
import com.starkgroup.vms.driver.DriverRepository;
import com.starkgroup.vms.maintenance.MaintenanceRecordRepository;
import com.starkgroup.vms.support.ApiTestSupport;
import com.starkgroup.vms.support.FleetApi;
import com.starkgroup.vms.vehicle.VehicleRepository;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * TC-12 / FR-DB01 / FR-X02: dashboard numbers match the data, against real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class DashboardApiTest {

    private static final byte[] PDF = "%PDF-1.4\n%dashboard\n".getBytes();

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleDriverAssignmentRepository assignmentRepository;
    private final VehicleDocumentRepository documentRepository;
    private final MaintenanceRecordRepository maintenanceRepository;
    private final StorageService storageService;
    private final Clock clock;

    private String adminToken;
    private String staffToken;

    @Autowired
    DashboardApiTest(MockMvc mockMvc,
                     JdbcTemplate jdbcTemplate,
                     PlatformTransactionManager transactionManager,
                     VehicleRepository vehicleRepository,
                     DriverRepository driverRepository,
                     VehicleDriverAssignmentRepository assignmentRepository,
                     VehicleDocumentRepository documentRepository,
                     MaintenanceRecordRepository maintenanceRepository,
                     StorageService storageService,
                     Clock clock) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.assignmentRepository = assignmentRepository;
        this.documentRepository = documentRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.storageService = storageService;
        this.clock = clock;
    }

    @BeforeEach
    void setUp() throws Exception {
        ApiTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
    }

    @Test
    void tc12_dashboardCountsAndCostMatchKnownData() throws Exception {
        LocalDate today = FleetApi.today();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        long active1 = FleetApi.createVehicle(mockMvc, staffToken, "DHK-0001");
        long active2 = FleetApi.createVehicle(mockMvc, staffToken, "DHK-0002");
        long workshop = FleetApi.createVehicle(mockMvc, staffToken, "DHK-0003");
        long retired = FleetApi.createVehicle(mockMvc, staffToken, "DHK-0004");

        uploadDocument(active1, "expired-long-ago.pdf", today.minusDays(30));
        uploadDocument(active1, "expired-yesterday.pdf", today.minusDays(1));
        uploadDocument(active2, "expires-today.pdf", today);
        uploadDocument(active2, "expires-in-30.pdf", today.plusDays(30));
        uploadDocument(workshop, "valid.pdf", today.plusDays(31));

        FleetApi.addMaintenance(mockMvc, staffToken, active1, today, "1000.50");
        FleetApi.addMaintenance(mockMvc, staffToken, workshop, firstOfMonth, "2000.25");
        FleetApi.addMaintenance(mockMvc, staffToken, retired, firstOfMonth.minusDays(1), "999.00");

        long rahim = FleetApi.createDriver(mockMvc, staffToken, FleetApi.driverJson("Rahim Uddin", "01711000001", "DK1")
                .put("licenseExpiry", today.minusDays(1).toString())).get("id").asLong();
        FleetApi.createDriver(mockMvc, staffToken, FleetApi.driverJson("Karim Hossain", "01811000002", "DK2")
                .put("licenseExpiry", today.plusDays(5).toString()));
        FleetApi.createDriver(mockMvc, staffToken, FleetApi.driverJson("Babul Akter", "01511000012", "DK3")
                .put("status", "INACTIVE"));
        FleetApi.assign(mockMvc, staffToken, active1, rahim);

        FleetApi.changeStatus(mockMvc, adminToken, workshop, "MAINTENANCE");
        FleetApi.changeStatus(mockMvc, adminToken, retired, "INACTIVE");

        mockMvc.perform(get("/api/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles.total").value(4))
                .andExpect(jsonPath("$.vehicles.active").value(2))
                .andExpect(jsonPath("$.vehicles.inactive").value(1))
                .andExpect(jsonPath("$.vehicles.maintenance").value(1))
                .andExpect(jsonPath("$.documents.expired").value(2))
                .andExpect(jsonPath("$.documents.expiringSoon").value(2))
                .andExpect(jsonPath("$.documents.withinDays").value(30))
                .andExpect(jsonPath("$.maintenance.month").value(today.toString().substring(0, 7)))
                .andExpect(jsonPath("$.maintenance.count").value(2))
                .andExpect(jsonPath("$.maintenance.totalCost").value(3000.75))
                .andExpect(jsonPath("$.drivers.total").value(3))
                .andExpect(jsonPath("$.drivers.active").value(2))
                .andExpect(jsonPath("$.drivers.assigned").value(1))
                .andExpect(jsonPath("$.drivers.licensesExpired").value(1))
                .andExpect(jsonPath("$.drivers.licensesExpiringSoon").value(1))
                .andExpect(jsonPath("$.expiringDocuments[*].fileName", contains("expired-long-ago.pdf",
                        "expired-yesterday.pdf", "expires-today.pdf", "expires-in-30.pdf")))
                .andExpect(jsonPath("$.expiringDocuments[0].expiryStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.expiringLicenses[*].fullName", contains("Rahim Uddin", "Karim Hossain")))
                .andExpect(jsonPath("$.expiringLicenses[0].licenseStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.expiringLicenses[0].currentVehicle.vehicleNumber").value("DHK-0001"))
                .andExpect(jsonPath("$.generatedAt").isString());
    }

    @Test
    void tc12_emptyDatabaseGivesZeros() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles.total").value(0))
                .andExpect(jsonPath("$.maintenance.totalCost").value(0))
                .andExpect(jsonPath("$.expiringDocuments").isEmpty())
                .andExpect(jsonPath("$.expiringLicenses").isEmpty());
        mockMvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    /**
     * TC-12 with the demo seeder (contract 6.10): the seeded data gives the designed numbers, and every dashboard
     * number equals an independent SQL count over the same tables.
     */
    @Test
    void tc12_seededDemoDataMatchesTheDatabase() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(vehicleRepository, driverRepository, assignmentRepository,
                documentRepository, maintenanceRepository, storageService, clock);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seeder.run());
        // Idempotent: a second run adds nothing.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seeder.run());

        JsonNode summary = ApiTestSupport.readJson(mockMvc.perform(get("/api/v1/dashboard/summary")
                .header(HttpHeaders.AUTHORIZATION, staffToken)).andExpect(status().isOk()).andReturn());

        // Designed seed: 26 vehicles (20/3/3), 12 drivers (11 active, 9 assigned), 20 documents (3 expired,
        // 5 expiring soon), 30 maintenance records with 6 in the current month.
        assertThat(summary.at("/vehicles/total").asLong()).isEqualTo(26);
        assertThat(summary.at("/vehicles/active").asLong()).isEqualTo(20);
        assertThat(summary.at("/vehicles/inactive").asLong()).isEqualTo(3);
        assertThat(summary.at("/vehicles/maintenance").asLong()).isEqualTo(3);
        assertThat(summary.at("/drivers/total").asLong()).isEqualTo(12);
        assertThat(summary.at("/drivers/active").asLong()).isEqualTo(11);
        assertThat(summary.at("/drivers/assigned").asLong()).isEqualTo(9);
        assertThat(summary.at("/drivers/licensesExpired").asLong()).isEqualTo(1);
        assertThat(summary.at("/drivers/licensesExpiringSoon").asLong()).isEqualTo(2);
        assertThat(summary.at("/documents/expired").asLong()).isEqualTo(3);
        assertThat(summary.at("/documents/expiringSoon").asLong()).isEqualTo(5);
        assertThat(summary.at("/maintenance/count").asLong()).isEqualTo(6);
        assertThat(summary.at("/maintenance/totalCost").decimalValue()).isEqualByComparingTo("27828.00");
        assertThat(summary.at("/expiringDocuments").size()).isEqualTo(8);
        assertThat(summary.at("/expiringLicenses").size()).isEqualTo(3);

        // Same numbers straight from SQL.
        assertThat(summary.at("/vehicles/active").asLong())
                .isEqualTo(count("SELECT count(*) FROM vehicles WHERE status = 'ACTIVE'"));
        assertThat(summary.at("/documents/expired").asLong())
                .isEqualTo(count("SELECT count(*) FROM vehicle_documents WHERE expiry_date < ?", FleetApi.today()));
        assertThat(summary.at("/documents/expiringSoon").asLong()).isEqualTo(count(
                "SELECT count(*) FROM vehicle_documents WHERE expiry_date BETWEEN ? AND ?", FleetApi.today(),
                FleetApi.today().plusDays(30)));
        assertThat(summary.at("/drivers/assigned").asLong())
                .isEqualTo(count("SELECT count(*) FROM vehicle_driver_assignments WHERE unassigned_at IS NULL"));
        LocalDate first = FleetApi.today().withDayOfMonth(1);
        BigDecimal monthCost = jdbcTemplate.queryForObject("SELECT coalesce(sum(cost), 0) FROM maintenance_records "
                + "WHERE service_date BETWEEN ? AND ?", BigDecimal.class, first, first.plusMonths(1).minusDays(1));
        assertThat(summary.at("/maintenance/totalCost").decimalValue()).isEqualByComparingTo(monthCost);
        assertThat(count("SELECT count(*) FROM maintenance_records")).isEqualTo(30);
        assertThat(count("SELECT count(*) FROM vehicle_driver_assignments WHERE unassigned_at IS NOT NULL"))
                .isPositive();
        assertThat(count("SELECT count(*) FROM stored_files")).isEqualTo(20);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private void uploadDocument(long vehicleId, String name, LocalDate expiry) throws Exception {
        mockMvc.perform(multipart("/api/v1/vehicles/" + vehicleId + "/documents")
                        .file(new MockMultipartFile("file", name, "application/pdf", PDF))
                        .param("documentType", "TAX_TOKEN")
                        .param("expiryDate", expiry.toString())
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isCreated());
    }
}
