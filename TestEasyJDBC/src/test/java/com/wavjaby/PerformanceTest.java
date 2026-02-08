package com.wavjaby;

import com.wavjaby.db.*;
import com.wavjaby.jdbc.util.RepositoryInit;
import com.wavjaby.jpa.DeviceJpa;
import com.wavjaby.jpa.DeviceJpaRepository;
import com.wavjaby.jpa.UserJpa;
import com.wavjaby.jpa.UserJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {
        AppConfig.class
})
public class PerformanceTest {

    @Autowired
    private UsersRepository easyJdbcUsersRepository;

    @Autowired
    private DeviceRepository easyJdbcDeviceRepository;

    @Autowired
    private UserJpaRepository jpaUserRepository;

    @Autowired
    private DeviceJpaRepository jpaDeviceRepository;

    @Autowired
    private RepositoryInit repositoryInit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    // Use smaller batch size for faster test execution
    private static final int BATCH_SIZE = 100000;
    private static final int ITERATIONS = 10;
    private static final int QUERY_TIMES = 400;

    @BeforeEach
    @SuppressWarnings("")
    public void setup() {
        cleanJpaData();
        cleanEasyJdbcData();
    }

    /**
     * Clean all JPA data including entities and cache
     */
    private void cleanJpaData() {
        // Clear JPA tables
        jpaDeviceRepository.deleteAll();
        jpaDeviceRepository.flush();
        jpaUserRepository.deleteAll();
        jpaUserRepository.flush();
        entityManager.getEntityManagerFactory().getCache().evictAll();
    }

    /**
     * Clean all EasyJDBC data using direct SQL
     */
    private void cleanEasyJdbcData() {
        jdbc.execute("DELETE FROM DEVICE WHERE TRUE");
        jdbc.execute("DELETE FROM USERS WHERE TRUE");
    }

    @Test
    @Transactional
    public void testInsertPerformance() {
        System.out.println("=== Insert Performance Test ===");

        // Insert Test
        long easyJdbcTotalTime = 0;
        long jpaTotalTime = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            long startTime = System.nanoTime();

            // Insert users with EasyJDBC
            for (int i = 0; i < BATCH_SIZE; i++) {
                User user = new User(
                        -1,
                        "user" + i,
                        "password",
                        "First" + i,
                        "Last" + i,
                        "123456" + i,
                        (byte) 0,
                        new String[]{"user" + i + "@example.com"},
                        "Address " + i,
                        new Date(System.currentTimeMillis() - ((20 + (i % 40)) * 365 * 24 * 60 * 60 * 1000L)),
                        new Timestamp(System.currentTimeMillis()),
                        true,
                        i % 100,
                        1000.0 + (i * 10.5),
                        (Long[]) null
                );
                user = easyJdbcUsersRepository.save(user);

                // Insert a device for each user
                final Device device = new Device(
                        -1,
                        user.userId(),
                        "Device" + i,
                        0.001 * i,
                        "SN" + (10000 + i),
                        "Model-" + (i % 10),
                        "Manufacturer-" + (i % 5),
                        new Timestamp(System.currentTimeMillis()),
                        new Timestamp(System.currentTimeMillis()),
                        i % 2 == 0,
                        i % 10,
                        "Description for device " + i
                );
                easyJdbcDeviceRepository.addDevice(device);
            }
            assertEquals(BATCH_SIZE, easyJdbcDeviceRepository.count());
            assertEquals(BATCH_SIZE, easyJdbcUsersRepository.count());

            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            easyJdbcTotalTime += duration;

            System.out.println("EasyJDBC Insert Iteration " + (iteration + 1) + ": " + duration + " ms");

            // Clean up for next iteration
            cleanEasyJdbcData();

            startTime = System.nanoTime();

            // Insert users with JPA
            for (int i = 0; i < BATCH_SIZE; i++) {
                UserJpa user = new UserJpa(
                        "user" + i,
                        "password",
                        "First" + i,
                        "Last" + i,
                        "654321" + i,
                        (byte) 0,
                        "jpauser" + i + "@example.com",
                        "JPA Address " + i,
                        new Date(System.currentTimeMillis() - ((20 + (i % 40)) * 365 * 24 * 60 * 60 * 1000L)),
                        new Timestamp(System.currentTimeMillis()),
                        true,
                        i % 100,
                        1000.0 + (i * 10.5)
                );
                user = jpaUserRepository.save(user);

                // Insert a device for each user
                DeviceJpa device = new DeviceJpa(
                        user,
                        "Device" + i,
                        0.001 * i,
                        "JPA-SN" + (10000 + i),
                        "JPA-Model-" + (i % 10),
                        "JPA-Manufacturer-" + (i % 5),
                        new Timestamp(System.currentTimeMillis()),
                        new Timestamp(System.currentTimeMillis()),
                        i % 2 == 0,
                        i % 10,
                        "JPA Description for device " + i
                );
                jpaDeviceRepository.save(device);
            }
            assertEquals(BATCH_SIZE, jpaUserRepository.count());
            assertEquals(BATCH_SIZE, jpaDeviceRepository.count());

            endTime = System.nanoTime();
            duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            jpaTotalTime += duration;

            System.out.println("JPA Insert Iteration " + (iteration + 1) + ": " + duration + " ms");

            // Clean up for next iteration
            cleanJpaData();
        }

        long easyJdbcAvgTime = easyJdbcTotalTime / ITERATIONS;
        System.out.println("EasyJDBC Average Insert Time: " + easyJdbcAvgTime + " ms");

        long jpaAvgTime = jpaTotalTime / ITERATIONS;
        System.out.println("JPA Average Insert Time: " + jpaAvgTime + " ms");

        System.out.println("Performance Difference: EasyJDBC " +
                String.format("%.2f", ((double) jpaAvgTime / easyJdbcAvgTime)) + "x " +
                (easyJdbcAvgTime > jpaAvgTime ? "slower" : "faster") + " than JPA");
    }

    @Test
    public void testQueryPerformance() {
        System.out.println("=== Query Performance Test ===");

        // Prepare test data
        List<User> easyJdbcUsers = new ArrayList<>();
        List<Device> easyJdbcDevices = new ArrayList<>();
        List<UserJpa> jpaUsers = new ArrayList<>();
        List<DeviceJpa> jpaDevices = new ArrayList<>();

        // Create test data for EasyJDBC
        for (int i = 0; i < BATCH_SIZE; i++) {
            User user = new User(
                    -1,
                    "user" + i,
                    "password",
                    "First" + i,
                    "Last" + i,
                    "123456" + i,
                    (byte) 0,
                    new String[]{"user" + i + "@example.com"},
                    "Address " + i,
                    new Date(System.currentTimeMillis() - ((20 + (i % 40)) * 365 * 24 * 60 * 60 * 1000L)),
                    new Timestamp(System.currentTimeMillis()),
                    true,
                    i % 100,
                    1000.0 + (i * 10.5),
                    null
            );
            user = easyJdbcUsersRepository.save(user);
            easyJdbcUsers.add(user);

            Device device = new Device(
                    -1,
                    user.userId(),
                    "Device" + i,
                    0.001 * i,
                    "SN" + (10000 + i),
                    "Model-" + (i % 10),
                    "Manufacturer-" + (i % 5),
                    new Timestamp(System.currentTimeMillis()),
                    new Timestamp(System.currentTimeMillis()),
                    i % 2 == 0,
                    i % 10,
                    "Description for device " + i
            );
            device = easyJdbcDeviceRepository.addDevice(device);
            easyJdbcDevices.add(device);
        }
        System.out.println("EasyJDBC test data ready: " + BATCH_SIZE + " records");

        // Create test data for JPA
        for (int i = 0; i < BATCH_SIZE; i++) {
            UserJpa user = new UserJpa(
                    "jpaUser" + i,
                    "password",
                    "First" + i,
                    "Last" + i,
                    "654321" + i,
                    (byte) 0,
                    "jpauser" + i + "@example.com",
                    "JPA Address " + i,
                    new Date(System.currentTimeMillis() - ((20 + (i % 40)) * 365 * 24 * 60 * 60 * 1000L)),
                    new Timestamp(System.currentTimeMillis()),
                    true,
                    i % 100,
                    1000.0 + (i * 10.5)
            );
            user = jpaUserRepository.save(user);
            jpaUsers.add(user);

            DeviceJpa device = new DeviceJpa(
                    user,
                    "JpaDevice" + i,
                    0.001 * i,
                    "JPA-SN" + (10000 + i),
                    "JPA-Model-" + (i % 10),
                    "JPA-Manufacturer-" + (i % 5),
                    new Timestamp(System.currentTimeMillis()),
                    new Timestamp(System.currentTimeMillis()),
                    i % 2 == 0,
                    i % 10,
                    "JPA Description for device " + i
            );
            device = jpaDeviceRepository.save(device);
            jpaDevices.add(device);
        }
        jpaUserRepository.flush();
        jpaDeviceRepository.flush();
        System.out.println("JPA test data ready: " + BATCH_SIZE + " records");

        // Query Test
        long easyJdbcTotalTime = 0;
        long jpaTotalTime = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            long startTime = System.nanoTime();

            // Query all devices with EasyJDBC
            List<Device> devices = easyJdbcDeviceRepository.getDevices();
            assertNotNull(devices);
            assertEquals(BATCH_SIZE, devices.size());

            // Query devices by name
            for (int i = 0; i < QUERY_TIMES; i++) {
                int index = (int) (Math.random() * BATCH_SIZE);
                List<Device> devicesByName = easyJdbcDeviceRepository.getDevicesByName("Device" + index);
                assertNotNull(devicesByName);
            }

            // Query devices by ID
            for (int i = 0; i < QUERY_TIMES; i++) {
                int index = (int) (Math.random() * BATCH_SIZE);
                Device device = easyJdbcDeviceRepository.getDeviceById(easyJdbcDevices.get(index).id());
                assertNotNull(device);
            }

            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            easyJdbcTotalTime += duration;

            System.out.println("EasyJDBC Query Iteration " + (iteration + 1) + ": " + duration + " ms");


            startTime = System.nanoTime();

            // Query all devices with JPA
            List<DeviceJpa> devices2 = jpaDeviceRepository.findAll();
            assertNotNull(devices2);
            assertEquals(BATCH_SIZE, devices2.size());

            // Query devices by name
            for (int i = 0; i < QUERY_TIMES; i++) {
                int index = (int) (Math.random() * BATCH_SIZE);
                List<DeviceJpa> devicesByName = jpaDeviceRepository.findByName("JpaDevice" + index);
                assertNotNull(devicesByName);
            }

            // Query devices by ID
            for (int i = 0; i < QUERY_TIMES; i++) {
                int index = (int) (Math.random() * BATCH_SIZE);
                DeviceJpa device = jpaDeviceRepository.findById(jpaDevices.get(index).getId()).orElse(null);
                assertNotNull(device);
            }

            endTime = System.nanoTime();
            duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            jpaTotalTime += duration;

            System.out.println("JPA Query Iteration " + (iteration + 1) + ": " + duration + " ms");
        }

        long easyJdbcAvgTime = easyJdbcTotalTime / ITERATIONS;
        System.out.println("EasyJDBC Average Query Time: " + easyJdbcAvgTime + " ms");

        long jpaAvgTime = jpaTotalTime / ITERATIONS;
        System.out.println("JPA Average Query Time: " + jpaAvgTime + " ms");

        System.out.println("Performance Difference: EasyJDBC " +
                String.format("%.2f", ((double) jpaAvgTime / easyJdbcAvgTime)) + "x " +
                (easyJdbcAvgTime > jpaAvgTime ? "slower" : "faster") + " than JPA");

        // Clean up
        cleanJpaData();
        cleanEasyJdbcData();
    }
}
