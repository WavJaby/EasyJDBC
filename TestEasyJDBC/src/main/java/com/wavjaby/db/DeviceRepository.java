package com.wavjaby.db;

import com.wavjaby.persistence.Count;
import com.wavjaby.persistence.Delete;
import com.wavjaby.persistence.Modifying;
import com.wavjaby.persistence.Where;

import java.sql.Timestamp;
import java.util.List;


public interface DeviceRepository {
    Device getDeviceById(long id);

    Device getDeviceByIdName(long id, String name);

    List<Device> getDevices();

    List<Device> getDevicesByName(String name);

    boolean checkDeviceById(long id);

    Device addDevice(Device newRow);

    @Modifying
    void updateDevice(@Where long id, long ownerId, String name, double numeric,
                     String serialNumber, String model, String manufacturer,
                     Timestamp creationDate, Timestamp lastUpdateDate,
                     boolean active, int firmwareVersion, String description);

    @Delete
    void deleteDeviceById(long id);
    
    @Count
    int count();
}
