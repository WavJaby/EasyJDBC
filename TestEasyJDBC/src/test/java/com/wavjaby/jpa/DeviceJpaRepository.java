package com.wavjaby.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceJpaRepository extends JpaRepository<DeviceJpa, Long> {
    List<DeviceJpa> findByName(String name);
}
