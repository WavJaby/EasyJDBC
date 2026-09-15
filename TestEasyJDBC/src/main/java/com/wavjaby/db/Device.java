package com.wavjaby.db;

import com.wavjaby.jdbc.annotation.*;

import java.sql.Timestamp;

@Table(repositoryClass = DeviceRepository.class)
public record Device(
        @Id
        @GenericGenerator()
        long id,
        @JoinColumn(referencedClass = User.class, referencedClassFieldName = "userId")
        long ownerId,
        @NotNull
        @Column(name = "NAME_STR")
        String name,
        @NotNull
        @Column(precision = 10, scale = 6)
        double numeric,

        // Store enum type as string
        @Column(length = 32)
        DeviceType typeString,

        // Store in PostgreSQL enum type
        DeviceType type,

        String serialNumber,
        String model,
        String manufacturer,
        Timestamp creationDate,
        Timestamp lastUpdateDate,
        boolean active,
        int firmwareVersion,
        String description) {
}
