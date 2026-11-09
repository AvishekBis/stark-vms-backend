package com.starkgroup.vms.document.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default storage: bytes in the {@code stored_files} table. The database is persistent on Render (the server
 * disk is not), and storing in the same transaction as the document metadata keeps both consistent.
 */
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = StorageProperties.DATABASE, matchIfMissing = true)
public class DatabaseStorageService implements StorageService {

    private final StoredFileRepository storedFileRepository;

    public DatabaseStorageService(StoredFileRepository storedFileRepository) {
        this.storedFileRepository = storedFileRepository;
    }

    @Override
    @Transactional
    public void store(String key, byte[] content, String contentType) {
        storedFileRepository.save(new StoredFile(key, contentType, content));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] load(String key) {
        return storedFileRepository.findByStorageKey(key)
                .map(StoredFile::getData)
                .orElseThrow(() -> new StorageException("Stored file " + key + " does not exist."));
    }

    @Override
    @Transactional
    public void delete(String key) {
        storedFileRepository.deleteByStorageKey(key);
    }
}
