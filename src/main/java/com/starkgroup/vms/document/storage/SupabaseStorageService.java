package com.starkgroup.vms.document.storage;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Optional storage in a private Supabase Storage bucket via its REST API
 * ({@code {SUPABASE_URL}/storage/v1/object/{bucket}/{key}}), authenticated with the service key. Enabled with
 * {@code app.storage.provider=supabase}. The service key stays on the server; clients always download through
 * the API with their JWT. An optional key prefix ({@code app.storage.supabase.key-prefix}, e.g. {@code prod/}) is
 * added to the object path only, so the storage key saved with the document stays the same in every environment.
 */
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = StorageProperties.SUPABASE)
public class SupabaseStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9._/-]*");

    private final RestClient restClient;
    private final String bucket;
    // /storage/v1/object/{bucket}/<prefix>{key}: the prefix is part of the template, so its "/" stays a separator
    // (as a URI variable it would be sent as %2F).
    private final String objectPath;

    public SupabaseStorageService(RestClient.Builder restClientBuilder, StorageProperties properties) {
        StorageProperties.Supabase supabase = properties.supabase();
        requireValue(supabase.url(), "SUPABASE_URL");
        requireValue(supabase.serviceKey(), "SUPABASE_SERVICE_KEY");
        requireValue(supabase.bucket(), "SUPABASE_BUCKET");
        this.bucket = supabase.bucket().trim();
        this.objectPath = "/storage/v1/object/{bucket}/" + normalizePrefix(supabase.keyPrefix()) + "{key}";
        this.restClient = restClientBuilder
                .baseUrl(stripTrailingSlash(supabase.url().trim()))
                .defaultHeader("Authorization", "Bearer " + supabase.serviceKey().trim())
                .defaultHeader("apikey", supabase.serviceKey().trim())
                .build();
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        try {
            restClient.post()
                    .uri(objectPath, bucket, key)
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("x-upsert", "false")
                    .body(content)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new StorageException("Could not upload file " + key + " to Supabase Storage.", ex);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            byte[] content = restClient.get()
                    .uri(objectPath, bucket, key)
                    .retrieve()
                    .body(byte[].class);
            if (content == null) {
                throw new StorageException("Supabase Storage returned no content for " + key + ".");
            }
            return content;
        } catch (RestClientException ex) {
            throw new StorageException("Could not download file " + key + " from Supabase Storage.", ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            restClient.delete()
                    .uri(objectPath, bucket, key)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                log.warn("File {} was already missing in Supabase Storage.", key);
                return;
            }
            throw new StorageException("Could not delete file " + key + " from Supabase Storage.", ex);
        } catch (RestClientException ex) {
            throw new StorageException("Could not delete file " + key + " from Supabase Storage.", ex);
        }
    }

    /**
     * Empty when not set; otherwise without leading slashes and with exactly one trailing slash. Only letters,
     * digits and {@code . _ - /} are allowed, so the prefix is a plain path and never a URI template variable.
     */
    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String trimmed = prefix.trim().replaceAll("^/+|/+$", "");
        if (!SAFE_PREFIX.matcher(trimmed).matches()) {
            throw new IllegalStateException("APP_STORAGE_KEY_PREFIX may only contain letters, digits and . _ - /");
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }

    private static void requireValue(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " must be set when app.storage.provider=supabase.");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
