package com.starkgroup.vms.maintenance;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.vehicle.VehicleUsage;

/** BR-10: a vehicle with maintenance records is not deleted. */
@Component
class MaintenanceVehicleUsage implements VehicleUsage {

    private final MaintenanceRecordRepository maintenanceRepository;

    MaintenanceVehicleUsage(MaintenanceRecordRepository maintenanceRepository) {
        this.maintenanceRepository = maintenanceRepository;
    }

    @Override
    public boolean isUsedBy(Long vehicleId) {
        return maintenanceRepository.existsByVehicleId(vehicleId);
    }
}
