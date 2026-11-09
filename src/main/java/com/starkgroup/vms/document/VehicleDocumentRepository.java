package com.starkgroup.vms.document;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleDocumentRepository extends JpaRepository<VehicleDocument, Long> {

    @EntityGraph(attributePaths = "vehicle")
    List<VehicleDocument> findByVehicleIdOrderByExpiryDateAscIdAsc(Long vehicleId);

    @EntityGraph(attributePaths = "vehicle")
    Optional<VehicleDocument> findWithVehicleById(Long id);

    /** Expired and expiring documents (expiry date on or before the given day). */
    @EntityGraph(attributePaths = "vehicle")
    Page<VehicleDocument> findByExpiryDateLessThanEqual(LocalDate date, Pageable pageable);

    boolean existsByVehicleId(Long vehicleId);

    long countByExpiryDateBefore(LocalDate date);

    long countByExpiryDateBetween(LocalDate fromInclusive, LocalDate toInclusive);
}
