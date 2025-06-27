package com.wavjaby.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserJpaRepository extends JpaRepository<UserJpa, Long> {
    UserJpa findByUsername(String username);
}
