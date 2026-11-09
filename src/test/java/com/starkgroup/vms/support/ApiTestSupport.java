package com.starkgroup.vms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helpers shared by the API tests. Accounts are the test-profile bootstrap users
 * (application-test.yml) - non-real credentials.
 */
public final class ApiTestSupport {

    public static final String ADMIN_EMAIL = "admin@stark.local";
    public static final String ADMIN_PASSWORD = "Admin@12345";
    public static final String STAFF_EMAIL = "staff@stark.local";
    public static final String STAFF_PASSWORD = "Staff@12345";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiTestSupport() {
    }

    public static String loginBody(String email, String password) {
        return JSON.createObjectNode().put("email", email).put("password", password).toString();
    }

    public static String bearerToken(MockMvc mockMvc, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + readJson(result).get("accessToken").asText();
    }

    public static String adminToken(MockMvc mockMvc) throws Exception {
        return bearerToken(mockMvc, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    public static String staffToken(MockMvc mockMvc) throws Exception {
        return bearerToken(mockMvc, STAFF_EMAIL, STAFF_PASSWORD);
    }

    public static JsonNode readJson(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
