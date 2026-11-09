package com.starkgroup.vms.document.storage;

import com.starkgroup.vms.common.persistence.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * File bytes kept in PostgreSQL (BYTEA) by {@link DatabaseStorageService}. Only read when a file is downloaded.
 */
@Entity
@Table(name = "stored_files")
public class StoredFile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, length = 100, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;

    protected StoredFile() {
    }

    public StoredFile(String storageKey, String contentType, byte[] data) {
        this.storageKey = storageKey;
        this.contentType = contentType;
        // No defensive copies: the entity owns the array and the bytes are never modified (saves a 5 MB copy).
        this.data = data;
        this.fileSize = data.length;
    }

    public Long getId() {
        return id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public byte[] getData() {
        return data;
    }
}
