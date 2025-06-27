package com.wavjaby.db;

import com.wavjaby.jdbc.Table;
import com.wavjaby.jdbc.util.Snowflake;
import com.wavjaby.persistence.*;

import java.sql.Timestamp;

@Table(repositoryClass = DeviceRepository.class)
public record Device(
        @Id
        @GenericGenerator(strategy = Snowflake.class)
        long id,
        @JoinColumn(referencedClass = User.class, referencedClassFieldName = "userId")
        long ownerId,
        @NotNull
        @Column(name = "NAME_STR")
        String name,
        @NotNull
        @Column(precision = 10, scale = 6)
        double numeric,

        String serialNumber,
        String model,
        String manufacturer,
        Timestamp creationDate,
        Timestamp lastUpdateDate,
        boolean active,
        int firmwareVersion,
        String description) {
}
