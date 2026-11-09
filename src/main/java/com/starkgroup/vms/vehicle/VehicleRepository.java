package com.starkgroup.vms.vehicle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VehicleRepository extends JpaRepository<Vehicle, Long>, JpaSpecificationExecutor<Vehicle> {

    boolean existsByNumberKey(String numberKey);

    boolean existsByNumberKeyAndIdNot(String numberKey, Long id);
}
