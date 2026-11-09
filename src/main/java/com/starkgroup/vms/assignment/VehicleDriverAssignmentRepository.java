package com.starkgroup.vms.assignment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleDriverAssignmentRepository extends JpaRepository<VehicleDriverAssignment, Long> {

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    Optional<VehicleDriverAssignment> findByVehicleIdAndUnassignedAtIsNull(Long vehicleId);

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    Optional<VehicleDriverAssignment> findByDriverIdAndUnassignedAtIsNull(Long driverId);

    /** Current drivers of a page of vehicles in one query (no N+1). */
    @Query("select a from VehicleDriverAssignment a join fetch a.driver "
            + "where a.vehicle.id in :vehicleIds and a.unassignedAt is null")
    List<VehicleDriverAssignment> findCurrentWithDriverByVehicleIds(@Param("vehicleIds") Collection<Long> vehicleIds);

    /** Current vehicles of a page of drivers in one query (no N+1). */
    @Query("select a from VehicleDriverAssignment a join fetch a.vehicle "
            + "where a.driver.id in :driverIds and a.unassignedAt is null")
    List<VehicleDriverAssignment> findCurrentWithVehicleByDriverIds(@Param("driverIds") Collection<Long> driverIds);

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    Page<VehicleDriverAssignment> findByVehicleId(Long vehicleId, Pageable pageable);

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    Page<VehicleDriverAssignment> findByDriverId(Long driverId, Pageable pageable);

    boolean existsByDriverId(Long driverId);

    boolean existsByVehicleId(Long vehicleId);

    /** Drivers that currently drive a vehicle (dashboard). */
    long countByUnassignedAtIsNull();
}
