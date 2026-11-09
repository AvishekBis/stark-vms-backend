package com.starkgroup.vms.maintenance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.starkgroup.vms.common.exception.GlobalExceptionHandler;
import com.starkgroup.vms.config.ValidationConfig;
import com.starkgroup.vms.maintenance.dto.MaintenanceRequest;
import com.starkgroup.vms.maintenance.dto.MaintenanceResponse;

/**
 * BR-09 at the DTO level ({@code @PastOrPresent}) with the application Clock wired into Bean Validation by
 * {@link ValidationConfig}: "today" is the date in Asia/Dhaka, not in the server's time zone.
 */
class MaintenanceRequestValidationTest {

    /** 17 Nov 2026 19:30 UTC is already 18 Nov 2026 01:30 in Dhaka (UTC+6). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T19:30:00Z"), ZoneId.of("Asia/Dhaka"));
    private static final LocalDate TODAY_IN_DHAKA = LocalDate.of(2026, 11, 18);
    private static final String HISTORY_PATH = "/api/v1/vehicles/12/maintenance";

    private final MaintenanceService maintenanceService = mock(MaintenanceService.class);
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.setConfigurationInitializer(new ValidationConfig().clockProviderCustomizer(CLOCK)::customize);
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new MaintenanceController(maintenanceService))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler(List.of()))
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void br09_tomorrowInDhakaIsA400FieldErrorBeforeTheService() throws Exception {
        mockMvc.perform(post(HISTORY_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(TODAY_IN_DHAKA.plusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("serviceDate"))
                .andExpect(jsonPath("$.errors[0].message").value("Service date cannot be in the future."));
        verifyNoInteractions(maintenanceService);
    }

    @Test
    void br09_todayInDhakaIsAcceptedAlthoughItIsStillYesterdayInUtc() throws Exception {
        when(maintenanceService.create(eq(12L), any(MaintenanceRequest.class))).thenReturn(new MaintenanceResponse(
                57L, 12L, "DHK-1234", TODAY_IN_DHAKA, MaintenanceType.OIL_CHANGE, new BigDecimal("100.00"), null,
                null, null, null, null, "staff@stark.local", 0L));

        mockMvc.perform(post(HISTORY_PATH).contentType(MediaType.APPLICATION_JSON).content(body(TODAY_IN_DHAKA)))
                .andExpect(status().isCreated());
    }

    private static String body(LocalDate serviceDate) {
        return """
                {"serviceDate": "%s", "maintenanceType": "OIL_CHANGE", "cost": 100}
                """.formatted(serviceDate);
    }
}
