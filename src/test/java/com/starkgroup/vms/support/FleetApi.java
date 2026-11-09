package com.starkgroup.vms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds v2.0 test data through the real API (so every rule runs), for the API tests. Fake names and numbers.
 */
public final class FleetApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FleetApi() {
    }

    /** Today in the business time zone, as the services see it with the real Clock. */
    public static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Dhaka"));
    }

    public static ObjectNode vehicleJson(String number) {
        return JSON.createObjectNode()
                .put("vehicleNumber", number)
                .put("vehicleType", "CAR")
                .put("brand", "Toyota")
                .put("model", "Axio")
                .put("manufactureYear", 2019)
                .put("color", "White")
                .put("fuelType", "OCTANE");
    }

    public static ObjectNode driverJson(String fullName, String phone, String licenseNumber) {
        return JSON.createObjectNode()
                .put("fullName", fullName)
                .put("phone", phone)
                .put("licenseNumber", licenseNumber)
                .put("licenseExpiry", today().plusYears(1).toString());
    }

    public static JsonNode createVehicle(MockMvc mockMvc, String token, ObjectNode body) throws Exception {
        return ApiTestSupport.readJson(mockMvc.perform(post("/api/v1/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn());
    }

    public static long createVehicle(MockMvc mockMvc, String token, String number) throws Exception {
        return createVehicle(mockMvc, token, vehicleJson(number)).get("id").asLong();
    }

    public static JsonNode createDriver(MockMvc mockMvc, String token, ObjectNode body) throws Exception {
        return ApiTestSupport.readJson(mockMvc.perform(post("/api/v1/drivers")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn());
    }

    public static long createDriver(MockMvc mockMvc, String token, String fullName, String phone,
                                    String licenseNumber) throws Exception {
        return createDriver(mockMvc, token, driverJson(fullName, phone, licenseNumber)).get("id").asLong();
    }

    public static JsonNode assign(MockMvc mockMvc, String token, long vehicleId, long driverId) throws Exception {
        return ApiTestSupport.readJson(mockMvc.perform(put("/api/v1/vehicles/" + vehicleId + "/driver")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":" + driverId + "}"))
                .andExpect(status().isOk())
                .andReturn());
    }

    public static void changeStatus(MockMvc mockMvc, String adminToken, long vehicleId, String status)
            throws Exception {
        mockMvc.perform(patch("/api/v1/vehicles/" + vehicleId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    public static ObjectNode maintenanceJson(LocalDate serviceDate, String cost) {
        return JSON.createObjectNode()
                .put("serviceDate", serviceDate.toString())
                .put("maintenanceType", "OIL_CHANGE")
                .put("cost", new java.math.BigDecimal(cost))
                .put("vendorName", "Navana Service Centre")
                .put("odometerKm", 45210)
                .put("notes", "Synthetic oil");
    }

    public static JsonNode addMaintenance(MockMvc mockMvc, String token, long vehicleId, LocalDate serviceDate,
                                          String cost) throws Exception {
        return ApiTestSupport.readJson(mockMvc.perform(post("/api/v1/vehicles/" + vehicleId + "/maintenance")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceJson(serviceDate, cost).toString()))
                .andExpect(status().isCreated())
                .andReturn());
    }
}
