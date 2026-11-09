package com.starkgroup.vms.document.storage;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByStorageKey(String storageKey);

    @Modifying
    @Query("delete from StoredFile f where f.storageKey = :storageKey")
    int deleteByStorageKey(@Param("storageKey") String storageKey);
}
