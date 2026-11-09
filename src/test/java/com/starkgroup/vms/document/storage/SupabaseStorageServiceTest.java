package com.starkgroup.vms.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Supabase Storage REST calls (contract 6.5) against a mocked server - no network.
 */
class SupabaseStorageServiceTest {

    private static final String OBJECT_URL =
            "https://demo-project.supabase.co/storage/v1/object/vehicle-documents/0b5e7c1e-key";
    private static final byte[] PDF = "%PDF-1.4 demo".getBytes();

    private MockRestServiceServer server;
    private SupabaseStorageService storage;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        storage = new SupabaseStorageService(builder, new StorageProperties(StorageProperties.SUPABASE,
                new StorageProperties.Supabase("https://demo-project.supabase.co/", "test-service-key",
                        "vehicle-documents", null)));
    }

    @Test
    void storeUploadsTheBytesWithTheServiceKey() {
        server.expect(requestTo(OBJECT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-service-key"))
                .andExpect(header("apikey", "test-service-key"))
                .andExpect(header("x-upsert", "false"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF))
                .andRespond(withSuccess("{\"Key\":\"vehicle-documents/0b5e7c1e-key\"}", MediaType.APPLICATION_JSON));

        storage.store("0b5e7c1e-key", PDF, "application/pdf");

        server.verify();
    }

    @Test
    void loadDownloadsTheBytes() {
        server.expect(requestTo(OBJECT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));

        assertThat(storage.load("0b5e7c1e-key")).isEqualTo(PDF);
        server.verify();
    }

    @Test
    void deleteOfAMissingObjectIsNotAnError() {
        server.expect(requestTo(OBJECT_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        storage.delete("0b5e7c1e-key");

        server.verify();
    }

    @Test
    void serverErrorsBecomeStorageExceptions() {
        server.expect(requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> storage.load("0b5e7c1e-key")).isInstanceOf(StorageException.class);
    }

    @Test
    void keyPrefixIsAddedToTheObjectPathOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer prefixedServer = MockRestServiceServer.bindTo(builder).build();
        SupabaseStorageService prefixed = new SupabaseStorageService(builder, new StorageProperties(
                StorageProperties.SUPABASE, new StorageProperties.Supabase("https://demo-project.supabase.co",
                        "test-service-key", "vehicle-documents", " /prod/ ")));
        String prefixedUrl = "https://demo-project.supabase.co/storage/v1/object/vehicle-documents/prod/0b5e7c1e-key";
        prefixedServer.expect(requestTo(prefixedUrl)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());
        prefixedServer.expect(requestTo(prefixedUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));
        prefixedServer.expect(requestTo(prefixedUrl)).andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess());

        prefixed.store("0b5e7c1e-key", PDF, "application/pdf");
        assertThat(prefixed.load("0b5e7c1e-key")).isEqualTo(PDF);
        prefixed.delete("0b5e7c1e-key");

        prefixedServer.verify();
    }

    @Test
    void keyPrefixWithUnsafeCharactersFailsAtStartup() {
        StorageProperties unsafe = new StorageProperties(StorageProperties.SUPABASE, new StorageProperties.Supabase(
                "https://demo-project.supabase.co", "test-service-key", "vehicle-documents", "prod/{key}"));

        assertThatThrownBy(() -> new SupabaseStorageService(RestClient.builder(), unsafe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STORAGE_KEY_PREFIX");
    }

    @Test
    void missingConfigurationFailsAtStartup() {
        StorageProperties incomplete = new StorageProperties(StorageProperties.SUPABASE,
                new StorageProperties.Supabase("https://demo-project.supabase.co", "", "vehicle-documents", null));

        assertThatThrownBy(() -> new SupabaseStorageService(RestClient.builder(), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPABASE_SERVICE_KEY");
    }
}
