package com.starkgroup.vms.document.storage;

/**
 * Where uploaded document files live (contract 6.5). The key is always a random UUID chosen by the server,
 * never the user's file name. Implementations are selected with {@code app.storage.provider}:
 * {@code database} (default, {@link DatabaseStorageService}) or {@code supabase} ({@link SupabaseStorageService}).
 */
public interface StorageService {

    void store(String key, byte[] content, String contentType);

    /**
     * @throws StorageException when the object does not exist or cannot be read
     */
    byte[] load(String key);

    /** Removes the object; a missing object is not an error. */
    void delete(String key);
}
