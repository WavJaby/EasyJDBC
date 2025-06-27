package com.wavjaby.jpa;

import jakarta.persistence.*;

import java.sql.Timestamp;

@Entity
@Table(name = "DEVICE_JPA")
public class DeviceJpa {
    @Id
    @SnowFlakeGenerator(name = "custom_sequence")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserJpa owner;

    @Column(name = "NAME_STR", nullable = false)
    private String name;

    @Column(precision = 10, nullable = false)
    private double numeric;

    private String serialNumber;
    private String model;
    private String manufacturer;
    private Timestamp creationDate;
    private Timestamp lastUpdateDate;
    private boolean active;
    private int firmwareVersion;
    private String description;

    public DeviceJpa() {
    }

    public DeviceJpa(UserJpa owner, String name, double numeric) {
        this.owner = owner;
        this.name = name;
        this.numeric = numeric;
    }

    public DeviceJpa(UserJpa owner, String name, double numeric, 
                    String serialNumber, String model, String manufacturer,
                    Timestamp creationDate, Timestamp lastUpdateDate,
                    boolean active, int firmwareVersion, String description) {
        this.owner = owner;
        this.name = name;
        this.numeric = numeric;
        this.serialNumber = serialNumber;
        this.model = model;
        this.manufacturer = manufacturer;
        this.creationDate = creationDate;
        this.lastUpdateDate = lastUpdateDate;
        this.active = active;
        this.firmwareVersion = firmwareVersion;
        this.description = description;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UserJpa getOwner() {
        return owner;
    }

    public void setOwner(UserJpa owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getNumeric() {
        return numeric;
    }

    public void setNumeric(double numeric) {
        this.numeric = numeric;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public Timestamp getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Timestamp creationDate) {
        this.creationDate = creationDate;
    }

    public Timestamp getLastUpdateDate() {
        return lastUpdateDate;
    }

    public void setLastUpdateDate(Timestamp lastUpdateDate) {
        this.lastUpdateDate = lastUpdateDate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(int firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
