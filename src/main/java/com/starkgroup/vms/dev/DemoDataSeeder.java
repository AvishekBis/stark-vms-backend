package com.starkgroup.vms.dev;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.assignment.VehicleDriverAssignment;
import com.starkgroup.vms.assignment.VehicleDriverAssignmentRepository;
import com.starkgroup.vms.config.ClockConfig;
import com.starkgroup.vms.document.DocumentType;
import com.starkgroup.vms.document.VehicleDocument;
import com.starkgroup.vms.document.VehicleDocumentRepository;
import com.starkgroup.vms.document.storage.StorageService;
import com.starkgroup.vms.driver.Driver;
import com.starkgroup.vms.driver.DriverPhone;
import com.starkgroup.vms.driver.DriverRepository;
import com.starkgroup.vms.driver.DriverStatus;
import com.starkgroup.vms.driver.LicenseNumber;
import com.starkgroup.vms.maintenance.MaintenanceRecord;
import com.starkgroup.vms.maintenance.MaintenanceRecordRepository;
import com.starkgroup.vms.maintenance.MaintenanceType;
import com.starkgroup.vms.vehicle.FuelType;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleNumber;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleStatus;
import com.starkgroup.vms.vehicle.VehicleType;

/**
 * Inserts realistic demo data into the real database for local development only (app.seed-demo-data=true, set
 * in the local profile): 26 vehicles; and (v2.0, contract 6.10) 12 drivers, 9 current plus 4 closed driver
 * assignments, 20 documents spread over EXPIRED / EXPIRING_SOON / VALID and 30 maintenance records over the last
 * six months, 6 of them in the current month. All dates are relative to "today" (injected Clock, Asia/Dhaka) so
 * the dashboard always shows interesting numbers. Idempotent: each part runs only when its tables are empty.
 * Names, phone and licence numbers are fake.
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

    private record DemoDriver(String name, String phone, String license, int licenseExpiryInDays,
                              DriverStatus status) {
    }

    private static final List<DemoDriver> DEMO_DRIVERS = List.of(
            new DemoDriver("Rahim Uddin", "01711-000001", "DK0123456L00001", 400, DriverStatus.ACTIVE),
            new DemoDriver("Karim Hossain", "01811-000002", "DK0123456L00002", 20, DriverStatus.ACTIVE),
            new DemoDriver("Jamal Ahmed", "01911-000003", "DK0123456L00003", -15, DriverStatus.ACTIVE),
            new DemoDriver("Sumon Mia", "01611-000004", "DK0123456L00004", 700, DriverStatus.ACTIVE),
            new DemoDriver("Rafiq Islam", "01511-000005", "DK0123456L00005", 9, DriverStatus.ACTIVE),
            new DemoDriver("Habib Rahman", "01311-000006", "DK0123456L00006", 365, DriverStatus.ACTIVE),
            new DemoDriver("Nasir Uddin", "01411-000007", "DK0123456L00007", 540, DriverStatus.ACTIVE),
            new DemoDriver("Faruk Hasan", "01711-000008", "DK0123456L00008", 250, DriverStatus.ACTIVE),
            new DemoDriver("Shahin Alam", "01811-000009", "DK0123456L00009", 800, DriverStatus.ACTIVE),
            new DemoDriver("Monir Hossain", "01911-000010", "DK0123456L00010", 180, DriverStatus.ACTIVE),
            new DemoDriver("Kamal Sheikh", "01611-000011", "DK0123456L00011", 90, DriverStatus.ACTIVE),
            new DemoDriver("Babul Akter", "01511-000012", "DK0123456L00012", 60, DriverStatus.INACTIVE));

    /** Number of ACTIVE vehicles that get a current driver (drivers 1..9). */
    private static final int CURRENT_ASSIGNMENTS = 9;

    /** Days until expiry of the seeded documents: 3 expired, 5 expiring soon, 12 valid. */
    private static final int[] DOCUMENT_EXPIRY_IN_DAYS =
            {-45, -10, -1, 0, 5, 10, 18, 27, 35, 60, 90, 120, 150, 200, 240, 300, 330, 365, 400, 500};

    private static final DocumentType[] DOCUMENT_TYPES = {DocumentType.TAX_TOKEN,
            DocumentType.FITNESS_CERTIFICATE, DocumentType.INSURANCE, DocumentType.REGISTRATION,
            DocumentType.ROUTE_PERMIT};

    private static final String[] VENDORS = {"Navana Service Centre", "Rangs Workshop", "Uttara Motors Service",
            "Karnaphuli Auto Care", "Shapla Tyre House"};

    private static final int MAINTENANCE_RECORDS = 30;
    private static final int MAINTENANCE_THIS_MONTH = 6;

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleDriverAssignmentRepository assignmentRepository;
    private final VehicleDocumentRepository documentRepository;
    private final MaintenanceRecordRepository maintenanceRepository;
    private final StorageService storageService;
    private final Clock clock;

    public DemoDataSeeder(VehicleRepository vehicleRepository, DriverRepository driverRepository,
                          VehicleDriverAssignmentRepository assignmentRepository,
                          VehicleDocumentRepository documentRepository,
                          MaintenanceRecordRepository maintenanceRepository, StorageService storageService,
                          Clock clock) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.assignmentRepository = assignmentRepository;
        this.documentRepository = documentRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.storageService = storageService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedVehicles();
        seedFleetData();
    }

    private void seedVehicles() {
        if (vehicleRepository.count() > 0) {
            log.info("Vehicles table is not empty; demo vehicles not seeded.");
            return;
        }
        List<Vehicle> vehicles = DEMO_VEHICLES.stream().map(DemoDataSeeder::toEntity).toList();
        vehicleRepository.saveAll(vehicles);
        log.info("Seeded {} demo vehicles.", vehicles.size());
    }

    private void seedFleetData() {
        if (driverRepository.count() > 0 || documentRepository.count() > 0 || maintenanceRepository.count() > 0) {
            log.info("Drivers, documents or maintenance already exist; v2.0 demo data not seeded.");
            return;
        }
        List<Vehicle> vehicles = vehicleRepository.findAll(Sort.by("id"));
        if (vehicles.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.BUSINESS_ZONE));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Driver> drivers = driverRepository.saveAll(
                DEMO_DRIVERS.stream().map(demo -> toEntity(demo, today)).toList());
        int assignments = seedAssignments(vehicles, drivers, now);
        int documents = seedDocuments(vehicles, today);
        int records = seedMaintenance(vehicles, today);
        log.info("Seeded {} drivers, {} assignments, {} documents and {} maintenance records.", drivers.size(),
                assignments, documents, records);
    }

    /**
     * Drivers 1..9 currently drive the first nine ACTIVE vehicles. Closed rows keep history: the previous
     * drivers of two of those vehicles, and drivers 10..12 who have no current vehicle.
     */
    private int seedAssignments(List<Vehicle> vehicles, List<Driver> drivers, Instant now) {
        List<Vehicle> active = vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE).toList();
        List<VehicleDriverAssignment> rows = new ArrayList<>();
        int current = Math.min(CURRENT_ASSIGNMENTS, Math.min(active.size(), drivers.size()));
        for (int i = 0; i < current; i++) {
            rows.add(new VehicleDriverAssignment(active.get(i), drivers.get(i), daysBefore(now, 10 + i * 7L)));
        }
        if (drivers.size() >= DEMO_DRIVERS.size() && active.size() >= 2) {
            rows.add(closed(active.get(0), drivers.get(9), daysBefore(now, 200), daysBefore(now, 10)));
            rows.add(closed(active.get(0), drivers.get(9), daysBefore(now, 420), daysBefore(now, 380)));
            rows.add(closed(active.get(1), drivers.get(11), daysBefore(now, 400), daysBefore(now, 17)));
            rows.add(closed(vehicles.get(vehicles.size() - 1), drivers.get(10), daysBefore(now, 300),
                    daysBefore(now, 120)));
        }
        assignmentRepository.saveAll(rows);
        return rows.size();
    }

    private int seedDocuments(List<Vehicle> vehicles, LocalDate today) {
        List<VehicleDocument> documents = new ArrayList<>();
        for (int i = 0; i < DOCUMENT_EXPIRY_IN_DAYS.length; i++) {
            Vehicle vehicle = vehicles.get(i % vehicles.size());
            DocumentType type = DOCUMENT_TYPES[i % DOCUMENT_TYPES.length];
            LocalDate expiry = today.plusDays(DOCUMENT_EXPIRY_IN_DAYS[i]);
            boolean pdf = i % 3 != 2;
            String baseName = type.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + vehicle.getNumberKey();
            byte[] content = pdf ? DemoFiles.pdf(type.name() + " " + vehicle.getVehicleNumber()) : DemoFiles.png();
            String contentType = pdf ? "application/pdf" : "image/png";
            String storageKey = UUID.randomUUID().toString();
            storageService.store(storageKey, content, contentType);
            documents.add(new VehicleDocument(vehicle, type, documentNumber(type, i), expiry.minusYears(1),
                    expiry, storageKey, baseName + (pdf ? ".pdf" : ".png"), contentType, content.length));
        }
        documentRepository.saveAll(documents);
        return documents.size();
    }

    /** 6 records in the current month (never after today) and 24 spread weekly over the previous months. */
    private int seedMaintenance(List<Vehicle> vehicles, LocalDate today) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        MaintenanceType[] types = MaintenanceType.values();
        List<MaintenanceRecord> records = new ArrayList<>();
        for (int i = 0; i < MAINTENANCE_RECORDS; i++) {
            LocalDate serviceDate = i < MAINTENANCE_THIS_MONTH
                    ? latest(firstOfMonth, today.minusDays(i * 2L))
                    : firstOfMonth.minusDays(3L + (i - MAINTENANCE_THIS_MONTH) * 7L);
            MaintenanceRecord record = new MaintenanceRecord(vehicles.get((i * 5) % vehicles.size()));
            record.setServiceDate(serviceDate);
            record.setMaintenanceType(types[i % types.length]);
            record.setCost(BigDecimal.valueOf(1200L + (i * 1375L) % 18000L).add(new BigDecimal("0.50")));
            record.setVendorName(VENDORS[i % VENDORS.length]);
            record.setOdometerKm(15000 + i * 2350);
            record.setNotes(i % 4 == 0 ? "Scheduled service as per manufacturer interval." : null);
            records.add(record);
        }
        maintenanceRepository.saveAll(records);
        return records.size();
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

    private static Driver toEntity(DemoDriver demo, LocalDate today) {
        Driver driver = new Driver(demo.name(), DriverPhone.normalize(demo.phone()), demo.license(),
                LicenseNumber.normalize(demo.license()), today.plusDays(demo.licenseExpiryInDays()));
        driver.setStatus(demo.status());
        return driver;
    }

    private static VehicleDriverAssignment closed(Vehicle vehicle, Driver driver, Instant from, Instant to) {
        VehicleDriverAssignment assignment = new VehicleDriverAssignment(vehicle, driver, from);
        assignment.close(to);
        return assignment;
    }

    private static String documentNumber(DocumentType type, int index) {
        String prefix = switch (type) {
            case TAX_TOKEN -> "TT";
            case FITNESS_CERTIFICATE -> "FC";
            case INSURANCE -> "INS";
            case REGISTRATION -> "REG";
            case ROUTE_PERMIT -> "RP";
            case OTHER -> "DOC";
        };
        return String.format("%s-%04d", prefix, 1001 + index);
    }

    private static Instant daysBefore(Instant now, long days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    private static LocalDate latest(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }
}
