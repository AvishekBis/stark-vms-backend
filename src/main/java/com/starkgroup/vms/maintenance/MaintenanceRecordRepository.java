package com.starkgroup.vms.maintenance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {

    @EntityGraph(attributePaths = "vehicle")
    Page<MaintenanceRecord> findByVehicleId(Long vehicleId, Pageable pageable);

    @EntityGraph(attributePaths = "vehicle")
    Optional<MaintenanceRecord> findWithVehicleById(Long id);

    /** Total cost over all records of a vehicle (FR-M02) - one SUM query, not the current page. */
    @Query("select coalesce(sum(m.cost), 0) from MaintenanceRecord m where m.vehicle.id = :vehicleId")
    BigDecimal sumCostByVehicleId(@Param("vehicleId") Long vehicleId);

    boolean existsByVehicleId(Long vehicleId);

    long countByServiceDateBetween(LocalDate fromInclusive, LocalDate toInclusive);

    /** Cost of all records with a service date in the given range (dashboard "this month"). */
    @Query("select coalesce(sum(m.cost), 0) from MaintenanceRecord m "
            + "where m.serviceDate between :fromInclusive and :toInclusive")
    BigDecimal sumCostByServiceDateBetween(@Param("fromInclusive") LocalDate fromInclusive,
                                           @Param("toInclusive") LocalDate toInclusive);
}
