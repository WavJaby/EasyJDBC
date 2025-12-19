package com.wavjaby.db;

import com.wavjaby.persistence.*;

import java.sql.Timestamp;
import java.util.List;


public interface DeviceRepository {
    Device addDevice(Device newRow);

    @BatchInsert
    int addDevice(List<Device> newRow);
    
    Device getDeviceById(long id);

    Device getDeviceByIdName(long id, String name);

    List<Device> getDevices();

    List<Device> getDevicesByName(String name);

    boolean checkDeviceById(long id);

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
