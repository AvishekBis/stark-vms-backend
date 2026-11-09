package com.starkgroup.vms.auth;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.starkgroup.vms.support.ApiTestSupport;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * Review finding SEC-1 against a real Tomcat (MockMvc has no valves): with server.forward-headers-strategy=native
 * the per-IP key is the right-most X-Forwarded-For entry that is not a trusted internal proxy - the address Render's
 * proxy appends. Client-supplied left-most entries and the RFC 7239 {@code Forwarded} header cannot choose the key,
 * so a client can neither block someone else's IP nor dodge its own limit by sending them. The test client
 * connects from 127.0.0.1, which is a trusted internal proxy like Render's proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class ClientIpApiTest {

    private static final String VICTIM_IP = "203.0.113.7";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final URI loginUri;

    @Autowired
    ClientIpApiTest(@LocalServerPort int port) {
        this.loginUri = URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login");
    }

    @Test
    void spoofedForwardedHeadersCannotBlockAChosenIp() throws Exception {
        String attackerIp = "198.51.100.77";
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> response = login(randomEmail(), "wrong-password",
                    "X-Forwarded-For", VICTIM_IP + ", " + attackerIp, "Forwarded", "for=" + VICTIM_IP);
            assertThat(response.statusCode()).isEqualTo(401);
        }

        assertThat(login(ApiTestSupport.STAFF_EMAIL, ApiTestSupport.STAFF_PASSWORD,
                "X-Forwarded-For", attackerIp).statusCode())
                .as("the address the proxy appended (right-most) is the one that is blocked").isEqualTo(429);
        assertThat(login(ApiTestSupport.STAFF_EMAIL, ApiTestSupport.STAFF_PASSWORD,
                "X-Forwarded-For", VICTIM_IP).statusCode())
                .as("the victim named in the left-most entry was never counted").isEqualTo(200);
        assertThat(login(ApiTestSupport.STAFF_EMAIL, ApiTestSupport.STAFF_PASSWORD,
                "Forwarded", "for=" + attackerIp).statusCode())
                .as("Forwarded is ignored: the key is the TCP peer 127.0.0.1").isEqualTo(200);
    }

    @Test
    void aBlockedClientCannotEscapeItsLimitWithAForgedLeftMostEntry() throws Exception {
        String clientIp = "198.51.100.78";
        for (int i = 0; i < 20; i++) {
            assertThat(login(randomEmail(), "wrong-password", "X-Forwarded-For", clientIp).statusCode())
                    .isEqualTo(401);
        }

        assertThat(login(ApiTestSupport.STAFF_EMAIL, ApiTestSupport.STAFF_PASSWORD,
                "X-Forwarded-For", "192.0.2.99, " + clientIp, "Forwarded", "for=192.0.2.99").statusCode())
                .isEqualTo(429);
    }

    private HttpResponse<String> login(String email, String password, String... headers)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(loginUri)
                .headers(headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ApiTestSupport.loginBody(email, password)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String randomEmail() {
        return "client-ip-" + UUID.randomUUID() + "@stark.local";
    }
}
