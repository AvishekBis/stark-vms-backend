package com.starkgroup.vms.dev;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.vehicle.FuelType;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleNumber;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleStatus;
import com.starkgroup.vms.vehicle.VehicleType;

/**
 * Inserts realistic demo vehicles into the real database for local development only
 * (app.seed-demo-data=true, set in the local profile). Runs only when the table is empty.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record DemoVehicle(String number, VehicleType type, String brand, String model, int year, String color,
                               FuelType fuel, VehicleStatus status) {
    }

    private static final List<DemoVehicle> DEMO_VEHICLES = List.of(
            new DemoVehicle("DHAKA METRO-GA 11-2345", VehicleType.CAR, "Toyota", "Axio", 2019, "White",
                    FuelType.OCTANE, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GA 13-4521", VehicleType.CAR, "Toyota", "Premio", 2020, "Pearl White",
                    FuelType.OCTANE, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GA 27-0198", VehicleType.CAR, "Toyota", "Allion", 2018, "Silver",
                    FuelType.CNG, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GHA 15-7781", VehicleType.CAR, "Honda", "Grace", 2017, "Black",
                    FuelType.HYBRID, VehicleStatus.MAINTENANCE),
            new DemoVehicle("DHAKA METRO-GA 31-2207", VehicleType.CAR, "Toyota", "Corolla Cross", 2023, "Grey",
                    FuelType.HYBRID, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GHA 11-9034", VehicleType.SUV, "Mitsubishi", "Pajero Sport", 2021, "Black",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GHA 12-4410", VehicleType.SUV, "Toyota", "Land Cruiser Prado", 2022,
                    "White", FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GHA 18-3356", VehicleType.SUV, "Nissan", "X-Trail", 2019, "Blue",
                    FuelType.OCTANE, VehicleStatus.INACTIVE),
            new DemoVehicle("DHAKA METRO-CHA 51-2093", VehicleType.MICROBUS, "Toyota", "Hiace", 2018, "White",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-CHA 53-1187", VehicleType.MICROBUS, "Toyota", "Hiace", 2020, "Silver",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-CHA 56-7742", VehicleType.MICROBUS, "Toyota", "Noah", 2016, "White",
                    FuelType.CNG, VehicleStatus.MAINTENANCE),
            new DemoVehicle("DHAKA METRO-BA 11-0567", VehicleType.BUS, "Hino", "AK1J", 2017, "Red",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-BA 15-3349", VehicleType.BUS, "Hino", "RN8J", 2019, "Blue",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-BA 12-8821", VehicleType.BUS, "Ashok Leyland", "Viking", 2015, "Green",
                    FuelType.DIESEL, VehicleStatus.INACTIVE),
            new DemoVehicle("CHATTA METRO-BA 11-4120", VehicleType.BUS, "Hino", "AK1J", 2021, "White",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-TA 14-2278", VehicleType.TRUCK, "Tata", "LPT 1613", 2016, "Yellow",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-TA 18-6603", VehicleType.TRUCK, "Tata", "Signa 1923", 2020, "Orange",
                    FuelType.DIESEL, VehicleStatus.MAINTENANCE),
            new DemoVehicle("DHAKA METRO-TA 20-1145", VehicleType.TRUCK, "Ashok Leyland", "Ecomet 1615", 2018,
                    "Blue", FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("CHATTA METRO-TA 11-3390", VehicleType.TRUCK, "Isuzu", "FVR", 2022, "White",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-NA 14-5521", VehicleType.PICKUP, "Toyota", "Hilux", 2021, "White",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-NA 16-0874", VehicleType.PICKUP, "Mahindra", "Bolero Pik-Up", 2019, "Silver",
                    FuelType.DIESEL, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-NA 19-3302", VehicleType.PICKUP, "Tata", "Xenon", 2017, "Red",
                    FuelType.DIESEL, VehicleStatus.INACTIVE),
            new DemoVehicle("DHAKA METRO-LA 22-6719", VehicleType.MOTORCYCLE, "Bajaj", "Pulsar 150", 2022, "Black",
                    FuelType.OCTANE, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-HA 35-1290", VehicleType.MOTORCYCLE, "Honda", "CB Hornet 160R", 2023, "Red",
                    FuelType.OCTANE, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-HA 41-8853", VehicleType.MOTORCYCLE, "Yamaha", "FZS V3", 2024, "Blue",
                    FuelType.OCTANE, VehicleStatus.ACTIVE),
            new DemoVehicle("DHAKA METRO-GA 45-0021", VehicleType.CAR, "BYD", "Atto 3", 2025, "Grey",
                    FuelType.ELECTRIC, VehicleStatus.ACTIVE));

    private final VehicleRepository vehicleRepository;

    public DemoDataSeeder(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (vehicleRepository.count() > 0) {
            log.info("Vehicles table is not empty; demo data not seeded.");
            return;
        }
        List<Vehicle> vehicles = DEMO_VEHICLES.stream().map(DemoDataSeeder::toEntity).toList();
        vehicleRepository.saveAll(vehicles);
        log.info("Seeded {} demo vehicles.", vehicles.size());
    }

    private static Vehicle toEntity(DemoVehicle demo) {
        Vehicle vehicle = new Vehicle(demo.number(), VehicleNumber.normalize(demo.number()), demo.type());
        vehicle.setBrand(demo.brand());
        vehicle.setModel(demo.model());
        vehicle.setManufactureYear((short) demo.year());
        vehicle.setColor(demo.color());
        vehicle.setFuelType(demo.fuel());
        vehicle.setStatus(demo.status());
        return vehicle;
    }
}
