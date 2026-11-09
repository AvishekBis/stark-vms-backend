package com.starkgroup.vms.vehicle;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starkgroup.vms.support.ApiTestSupport;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * ADR 0023 / contract 6.2 on vehicles against the real database: version in the responses, required in PUT,
 * 409 stale-version, ignored in POST, a lost race between read and flush, and migration V2_1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class VehicleOptimisticLockingApiTest {

    private static final String VEHICLES = "/api/v1/vehicles";
    private static final String STALE = "https://stark-vms/errors/stale-version";
    private static final String STALE_DETAIL =
            "This vehicle was changed by someone else since you opened it. Reload and try again.";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final VehicleRepository vehicleRepository;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;

    private String adminToken;
    private String staffToken;

    @Autowired
    VehicleOptimisticLockingApiTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                                    VehicleRepository vehicleRepository, TransactionTemplate transactionTemplate,
                                    DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.vehicleRepository = vehicleRepository;
        this.transactionTemplate = transactionTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM vehicles");
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
    }

    @Test
    void secondSaveWithTheSameVersionIsStaleAndChangesNothing() throws Exception {
        JsonNode created = create(vehicleJson("DHK-1234").put("version", 99));
        long id = created.get("id").asLong();
        assertThat(created.get("version").asLong()).as("a version in POST is ignored").isZero();

        putJson(id, vehicleJson("DHK-1234").put("model", "Premio").put("version", 0), staffToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("Premio"))
                .andExpect(jsonPath("$.version").value(1));

        putJson(id, vehicleJson("DHK-1234").put("model", "Allion").put("version", 0), adminToken)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(STALE))
                .andExpect(jsonPath("$.title").value("Record changed"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(STALE_DETAIL))
                .andExpect(jsonPath("$.instance").value(VEHICLES + "/" + id))
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(get(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.model").value("Premio"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get(VEHICLES).header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(jsonPath("$.content[0].version").value(1));
    }

    @Test
    void aNewerVersionThanTheStoredOneIsAlsoStale() throws Exception {
        long id = create(vehicleJson("DHK-1234")).get("id").asLong();

        putJson(id, vehicleJson("DHK-1234").put("model", "Premio").put("version", 5), staffToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(STALE));
    }

    @Test
    void staleVersionWinsOverADuplicateNumber() throws Exception {
        create(vehicleJson("CTG-9999"));
        long id = create(vehicleJson("DHK-1234")).get("id").asLong();
        putJson(id, vehicleJson("DHK-1234").put("model", "Premio").put("version", 0), staffToken)
                .andExpect(status().isOk());

        putJson(id, vehicleJson("CTG-9999").put("version", 0), staffToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(STALE));
    }

    /** Contract 6.2: a PUT that changes no value writes nothing, so the version stays and an open form stays valid. */
    @Test
    void putWithUnchangedValuesKeepsTheVersion() throws Exception {
        long id = create(vehicleJson("DHK-1234")).get("id").asLong();

        for (int i = 0; i < 2; i++) {
            putJson(id, vehicleJson("DHK-1234").put("version", 0), staffToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(0));
        }
    }

    @Test
    void putWithoutVersionIs400OnTheVersionField() throws Exception {
        long id = create(vehicleJson("DHK-1234")).get("id").asLong();

        putJson(id, vehicleJson("DHK-1234"), staffToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("version"))
                .andExpect(jsonPath("$.errors[0].message").value("Version is required."));
        putJson(id, vehicleJson("DHK-1234").putNull("version"), staffToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    /**
     * The race the service-level check cannot see: another transaction commits between our read and our flush.
     * Hibernate's versioned UPDATE then matches no row; the exception is the one GlobalExceptionHandler maps to the
     * same 409 stale-version.
     */
    @Test
    void aConcurrentCommitBetweenReadAndFlushIsAnOptimisticLockingFailure() throws Exception {
        long id = create(vehicleJson("DHK-1234")).get("id").asLong();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Vehicle loaded = vehicleRepository.findById(id).orElseThrow();
            commitConcurrentChange(id);
            loaded.setModel("Allion");
            vehicleRepository.saveAndFlush(loaded);
        }))
                .isInstanceOfSatisfying(ObjectOptimisticLockingFailureException.class,
                        ex -> assertThat(ex.getPersistentClassName()).isEqualTo(Vehicle.class.getName()));
        assertThat(jdbcTemplate.queryForObject("SELECT model FROM vehicles WHERE id = ?", String.class, id))
                .isEqualTo("Premio");
    }

    @Test
    void migrationV2_1AddedTheVersionColumn() {
        assertThat(jdbcTemplate.queryForObject("SELECT data_type || ':' || is_nullable || ':' || column_default "
                + "FROM information_schema.columns WHERE table_name = 'vehicles' AND column_name = 'version'",
                String.class)).isEqualTo("bigint:NO:0");
    }

    /** Another client's change, committed on its own connection (not part of the test's transaction). */
    private void commitConcurrentChange(long id) {
        try (Connection other = dataSource.getConnection();
             PreparedStatement update = other.prepareStatement(
                     "UPDATE vehicles SET model = 'Premio', version = version + 1 WHERE id = ?")) {
            other.setAutoCommit(true);
            update.setLong(1, id);
            update.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode create(ObjectNode body) throws Exception {
        MvcResult result = mockMvc.perform(post(VEHICLES)
                        .header(HttpHeaders.AUTHORIZATION, staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return ApiTestSupport.readJson(result);
    }

    private ResultActions putJson(long id, ObjectNode body, String token) throws Exception {
        return mockMvc.perform(put(VEHICLES + "/" + id).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
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
