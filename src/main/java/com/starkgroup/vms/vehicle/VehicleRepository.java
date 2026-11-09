package com.starkgroup.vms.vehicle;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface VehicleRepository extends JpaRepository<Vehicle, Long>, JpaSpecificationExecutor<Vehicle> {

    boolean existsByNumberKey(String numberKey);

    boolean existsByNumberKeyAndIdNot(String numberKey, Long id);

    /**
     * Loads the vehicle with a row lock (SELECT ... FOR UPDATE) for the rest of the transaction, so a driver
     * assignment and a status change of the same vehicle cannot interleave (BR-05: only ACTIVE vehicles and
     * drivers have a current assignment).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findForUpdateById(@Param("id") Long id);

    /** Vehicles per status in one GROUP BY query (dashboard, FR-DB01). */
    @Query("select v.status as status, count(v) as total from Vehicle v group by v.status")
    List<StatusCount> countByStatus();

    interface StatusCount {

        VehicleStatus getStatus();

        long getTotal();
    }
}
