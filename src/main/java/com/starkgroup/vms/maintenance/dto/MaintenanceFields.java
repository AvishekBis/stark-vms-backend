package com.starkgroup.vms.maintenance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.starkgroup.vms.maintenance.MaintenanceType;

/**
 * Fields shared by the create and update requests ({@link MaintenanceRequest}, {@link UpdateMaintenanceRequest}),
 * so the mapper and service treat both alike.
 */
public interface MaintenanceFields {

    LocalDate serviceDate();

    MaintenanceType maintenanceType();

    BigDecimal cost();

    String vendorName();

    Integer odometerKm();

    String notes();
}
