package com.wavjaby;

import com.wavjaby.db.*;
import com.wavjaby.jdbc.util.RepositoryInit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {
        AppConfig.class
})
public class SimplePerformanceTest {
    private static final Logger log = LoggerFactory.getLogger(SimplePerformanceTest.class);
    @Autowired
    private UsersRepository easyJdbcUsersRepository;

    @Autowired
    private DeviceRepository easyJdbcDeviceRepository;

    @Autowired
    private RepositoryInit repositoryInit;

    @Autowired
    private JdbcTemplate jdbc;


    // Use smaller batch size for faster test execution
    private static final int BATCH_SIZE = 100;
    private static final int ITERATIONS = 3;

    @BeforeEach
    public void setup() {
        // Clear tables
        jdbc.execute("DELETE FROM DEVICE WHERE TRUE");
        jdbc.execute("DELETE FROM USERS WHERE TRUE");
    }

    @Test
    public void testEasyJdbcPerformance() {
        System.out.println("=== EasyJDBC Performance Test ===");

        // Insert Test
        long insertTotalTime = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            long startTime = System.nanoTime();

            // Insert users with EasyJDBC
            List<User> users = new ArrayList<>();
            for (int i = 0; i < BATCH_SIZE; i++) {
                User user = new User(-1, "user" + i, "password", "First" + i, "Last" + i, "123456" + i, (byte) 0,
                        new String[]{"user" + i + "@example.com"}, "Address" + i, null, null, true, 0, 0.0, (Long[]) null);
                user = easyJdbcUsersRepository.save(user);
                users.add(user);

                // Insert a device for each user with a unique ID
                Device device = new Device(i + 1, user.userId(), "Device" + i, 1.2 + i,
                        "SN" + i, "Model" + i, "Manufacturer" + i, null, null, true, 1, "Description" + i);
                easyJdbcDeviceRepository.addDevice(device);
            }

            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            insertTotalTime += duration;

            System.out.println("EasyJDBC Insert Iteration " + (iteration + 1) + ": " + duration + " ms");


            // Clean up for next iteration
            jdbc.execute("DELETE FROM DEVICE WHERE TRUE");
            jdbc.execute("DELETE FROM USERS WHERE TRUE");
        }

        long insertAvgTime = insertTotalTime / ITERATIONS;
        System.out.println("EasyJDBC Average Insert Time: " + insertAvgTime + " ms");

        // Query Test
        long queryTotalTime = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            long startTime = System.nanoTime();

            // Query all devices with EasyJDBC
            List<Device> devices = easyJdbcDeviceRepository.getDevices();
            assertNotNull(devices);

            // Query devices by name
            for (int i = 0; i < 10; i++) {
                int index = (int) (Math.random() * BATCH_SIZE);
                List<Device> devicesByName = easyJdbcDeviceRepository.getDevicesByName("Device" + index);
                assertNotNull(devicesByName);
            }

            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            queryTotalTime += duration;

            System.out.println("EasyJDBC Query Iteration " + (iteration + 1) + ": " + duration + " ms");
        }

        long queryAvgTime = queryTotalTime / ITERATIONS;
        System.out.println("EasyJDBC Average Query Time: " + queryAvgTime + " ms");

        // Summary
        System.out.println("\n=== Performance Summary ===");
        System.out.println("EasyJDBC Average Insert Time: " + insertAvgTime + " ms");
        System.out.println("EasyJDBC Average Query Time: " + queryAvgTime + " ms");
        System.out.println("Total Average Time: " + (insertAvgTime + queryAvgTime) + " ms");
    }
}
