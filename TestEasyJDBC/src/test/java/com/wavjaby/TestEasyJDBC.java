package com.wavjaby;

import com.wavjaby.db.*;
import com.wavjaby.jdbc.util.RepositoryInit;
import com.wavjaby.jdbc.util.Snowflake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@SpringBootTest(classes = {
        AppConfig.class,
        Snowflake.class,
        UsersRepositoryImpl.class,
        DeviceRepositoryImpl.class,
        RepositoryInit.class
})
public class TestEasyJDBC {
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private UsersRepository usersRepository;

    @AfterAll
    public static void cleanup() throws IOException {
        File testRoot = new File(System.getProperty("user.dir"));
//        System.out.println(testRoot.getAbsolutePath());
    }

    @Test
    public void databaseTest() {
        // Create and add test user
        User user = new User(-1, "username", "password", "firstname", "lastname", "01234", (byte) 0, 
            "username@example.com", "Test Address", null, null, true, 0, 0.0);
        user = usersRepository.addUser(user);

        // Add device for user
        Device device = new Device(123, user.userId(), "name", 1.2, 
            "SN123", "TestModel", "TestManufacturer", null, null, true, 1, "Test device");
        device = deviceRepository.addDevice(device);

        // Get device by id
        device = deviceRepository.getDeviceById(device.id());
        assertNotNull(device);
        assertEquals(device.ownerId(), user.userId());

        // Delete device
        deviceRepository.deleteDeviceById(device.id());
        device = deviceRepository.getDeviceById(device.id());
        Assertions.assertNull(device);
    }
}
