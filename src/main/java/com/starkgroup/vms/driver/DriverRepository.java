package com.starkgroup.vms.driver;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface DriverRepository extends JpaRepository<Driver, Long>, JpaSpecificationExecutor<Driver> {

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    boolean existsByLicenseKey(String licenseKey);

    boolean existsByLicenseKeyAndIdNot(String licenseKey, Long id);

    /**
     * Loads the driver with a row lock (SELECT ... FOR UPDATE) for the rest of the transaction, so assigning the
     * driver and setting them INACTIVE cannot interleave (BR-05).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id")
    Optional<Driver> findForUpdateById(@Param("id") Long id);

    long countByStatus(DriverStatus status);

    long countByLicenseExpiryBefore(LocalDate date);

    long countByLicenseExpiryBetween(LocalDate fromInclusive, LocalDate toInclusive);

    /** Expired and expiring-soon licences, soonest first (dashboard, FR-X02). */
    List<Driver> findByLicenseExpiryLessThanEqual(LocalDate date, Pageable pageable);
}
