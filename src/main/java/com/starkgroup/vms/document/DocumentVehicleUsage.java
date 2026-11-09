package com.starkgroup.vms.document;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.vehicle.VehicleUsage;

/** BR-10: a vehicle with documents is not deleted. */
@Component
class DocumentVehicleUsage implements VehicleUsage {

    private final VehicleDocumentRepository documentRepository;

    DocumentVehicleUsage(VehicleDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public boolean isUsedBy(Long vehicleId) {
        return documentRepository.existsByVehicleId(vehicleId);
    }
}
