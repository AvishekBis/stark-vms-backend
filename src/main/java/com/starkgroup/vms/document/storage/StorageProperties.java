package com.starkgroup.vms.document.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.storage.*}: {@code provider} is {@code database} (default) or {@code supabase}. The Supabase values
 * come from SUPABASE_URL, SUPABASE_SERVICE_KEY, SUPABASE_BUCKET and the optional APP_STORAGE_KEY_PREFIX (e.g.
 * {@code prod/}, so dev and prod can share a bucket without mixing objects) and are only used by that provider.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String provider, Supabase supabase) {

    public static final String DATABASE = "database";
    public static final String SUPABASE = "supabase";

    public StorageProperties {
        provider = provider == null || provider.isBlank() ? DATABASE : provider.trim();
        supabase = supabase == null ? new Supabase(null, null, null, null) : supabase;
    }

    public record Supabase(String url, String serviceKey, String bucket, String keyPrefix) {
    }
}
