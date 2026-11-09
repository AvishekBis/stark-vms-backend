package com.starkgroup.vms.maintenance;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.common.util.TextValues;
import com.starkgroup.vms.maintenance.dto.MaintenanceFields;
import com.starkgroup.vms.maintenance.dto.MaintenanceResponse;

/**
 * Entity to DTO mapping. Optional text fields are trimmed and blank values stored as null.
 * Call inside a transaction: the vehicle is a lazy association.
 */
@Component
public class MaintenanceMapper {

    public void copy(MaintenanceFields request, MaintenanceRecord record) {
        record.setServiceDate(request.serviceDate());
        record.setMaintenanceType(request.maintenanceType());
        record.setCost(toMoney(request.cost()));
        record.setVendorName(TextValues.trimToNull(request.vendorName()));
        record.setOdometerKm(request.odometerKm());
        record.setNotes(TextValues.trimToNull(request.notes()));
    }

    /**
     * NUMERIC(12,2): the scale is fixed to 2 decimals so the response right after a write looks exactly like a
     * later read (3500 becomes 3500.00). The service has already rejected more than 2 significant decimals.
     */
    static BigDecimal toMoney(BigDecimal cost) {
        return cost == null ? null : cost.setScale(2, RoundingMode.UNNECESSARY);
    }

    public MaintenanceResponse toResponse(MaintenanceRecord record) {
        return new MaintenanceResponse(
                record.getId(),
                record.getVehicle().getId(),
                record.getVehicle().getVehicleNumber(),
                record.getServiceDate(),
                record.getMaintenanceType(),
                record.getCost(),
                record.getVendorName(),
                record.getOdometerKm(),
                record.getNotes(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                record.getCreatedBy(),
                record.getVersion());
    }
}
