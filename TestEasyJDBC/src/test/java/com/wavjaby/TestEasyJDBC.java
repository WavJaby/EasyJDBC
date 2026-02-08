package com.wavjaby;

import com.wavjaby.db.*;
import com.wavjaby.jdbc.util.RepositoryInit;
import com.wavjaby.jdbc.util.Snowflake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = {
        AppConfig.class
})
public class TestEasyJDBC {
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private FriendRepository friendRepository;
    @Autowired
    private RepositoryInit repositoryInit;

    @BeforeEach
    public void setup() {
        repositoryInit.initSchemeAndTable();
    }

    @AfterAll
    public static void cleanup() throws IOException {
        File testRoot = new File(System.getProperty("user.dir"));
//        System.out.println(testRoot.getAbsolutePath());
    }

    @Test
    public void databaseTest() {
        // Create and add test user
        User user = new User(-1, "username", "password", "firstname", "lastname", "01234", (byte) 0, 
            new String[]{"username@example.com"}, "Test Address", null, null, true, 0, 0.0, null);
        user = usersRepository.save(user);

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

    @Test
    public void testGetUserIdsAndUsernames() {
        // Add multiple users
        User user1 = new User(-1, "user1", "pass1", "f1", "l1", "111", (byte) 0, new String[]{"u1@e.com"}, "a1", null, null, true, 0, 0.0, null);
        User user2 = new User(-1, "user2", "pass2", "f2", "l2", "222", (byte) 0, new String[]{"u2@e.com"}, "a2", null, null, true, 0, 0.0, null);
        user1 = usersRepository.save(user1);
        user2 = usersRepository.save(user2);

        Set<Long> expectedIds = new HashSet<>(Arrays.asList(user1.userId(), user2.userId()));
        Set<String> expectedUsernames = new HashSet<>(Arrays.asList("user1", "user2"));

        // Test getUserIds
        List<Long> userIds = usersRepository.getUserIds();
        assertNotNull(userIds);
        assertTrue(userIds.size() >= 2);
        Set<Long> actualIds = new HashSet<>(userIds);
        assertTrue(actualIds.containsAll(expectedIds));

        // Test getUsernames
        List<String> usernames = usersRepository.getUsernames();
        assertNotNull(usernames);
        assertTrue(usernames.size() >= 2);
        Set<String> actualUsernames = new HashSet<>(usernames);
        assertTrue(actualUsernames.containsAll(expectedUsernames));
    }

    @Test
    public void testGetEmailsAndDeviceIds() {
        String[] emails = {"test1@example.com", "test2@example.com"};
        Long[] deviceIds = {111L, 222L};
        User user = new User(-1, "testuser", "pass", "f", "l", "123", (byte) 0,
                emails, "addr", null, null, true, 0, 0.0, deviceIds);
        usersRepository.save(user);

        // Test getEmails
        List<String[]> allEmails = usersRepository.getEmails();
        assertNotNull(allEmails);
        boolean found = false;
        for (String[] e : allEmails) {
            if (Arrays.equals(e, emails)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Added emails not found in getEmails()");

        // Test getDeviceIds
        List<Long[]> allDeviceIds = usersRepository.getDeviceIds();
        assertNotNull(allDeviceIds);
        found = false;
        for (Long[] d : allDeviceIds) {
            if (Arrays.equals(d, deviceIds)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Added deviceIds not found in getDeviceIds()");
    }

    @Test
    public void testGetDeviceIdByUserId() {
        Long[] deviceIds = {123L, 456L, 789L};
        User user = new User(-1, "deviceuser", "pass", "f", "l", "999", (byte) 0,
                new String[]{"device@example.com"}, "addr", null, null, true, 0, 0.0, deviceIds);
        user = usersRepository.save(user);

        Long[] actualDeviceIds = usersRepository.getDeviceIdByUserId(user.userId());
        assertNotNull(actualDeviceIds);
        Assertions.assertArrayEquals(deviceIds, actualDeviceIds);
    }

    @Test
    public void friendRepositoryTest() {
        // Create two users with unique usernames
        User user1 = new User(-1, "friendUser1", "pass1", "f1", "l1", "111", (byte) 0, new String[]{"u1@e.com"}, "a1", null, null, true, 0, 0.0, null);
        User user2 = new User(-1, "friendUser2", "pass2", "f2", "l2", "222", (byte) 0, new String[]{"u2@e.com"}, "a2", null, null, true, 0, 0.0, null);
        user1 = usersRepository.save(user1);
        user2 = usersRepository.save(user2);
        long id1 = user1.userId();
        long id2 = user2.userId();

        // 1. Send friend request (save)
        Friend request = new Friend(id1, id2, null);
        friendRepository.save(request);

        // 2. Check pending request
        assertTrue(friendRepository.isRequestPending(id1, id2));
        assertTrue(friendRepository.isRequestExist(id1, id2));
        Assertions.assertNull(friendRepository.getAcceptState(id1, id2));

        // 3. Get requests for user 2
        List<Long> requests = friendRepository.getRequests(id2);
        assertTrue(requests.contains(id1));

        // 4. Accept friend request
        friendRepository.setAcceptState(id1, id2, true);
        assertTrue(friendRepository.isFriend(id1, id2));
        assertTrue(friendRepository.isFriend(id2, id1)); // Symmetric check
        assertEquals(true, friendRepository.getAcceptState(id1, id2));

        // 5. Get friend IDs
        List<Long> friendsOf1 = friendRepository.getFriendIds(id1);
        assertTrue(friendsOf1.contains(id2));
        List<Long> friendsOf2 = friendRepository.getFriendIds(id2);
        assertTrue(friendsOf2.contains(id1));

        // 5.1 Test complex query
        assertEquals(1, friendRepository.countFriends(id1));
        List<Long> complexFriendsOf1 = friendRepository.getFriendIdsComplex(id1);
        assertTrue(complexFriendsOf1.contains(id2));

        // 6. Delete friend
        assertTrue(friendRepository.delete(id1, id2));
        Assertions.assertFalse(friendRepository.isFriend(id1, id2));

        // 7. Send another request and delete it
        friendRepository.save(new Friend(id2, id1, null));
        assertTrue(friendRepository.isRequestPending(id2, id1));
        assertTrue(friendRepository.deleteRequest(id2, id1));
        Assertions.assertFalse(friendRepository.isRequestPending(id2, id1));
    }
}
