package com.wavjaby.db;


import com.wavjaby.jdbc.Table;
import com.wavjaby.jdbc.util.Snowflake;
import com.wavjaby.persistence.Column;
import com.wavjaby.persistence.GenericGenerator;
import com.wavjaby.persistence.Id;
import com.wavjaby.persistence.UniqueConstraint;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

@Table(name = "USERS", repositoryClass = UsersRepository.class, uniqueConstraints = {
        @UniqueConstraint(fieldNames = {"username", "phoneNumber"})
})
public record User(
        @Id
        @GenericGenerator(strategy = Snowflake.class)
        long userId,
        @Column(unique = true)
        String username,
        String password,

        String firstName,
        String lastName,
        String phoneNumber,
        byte gender,

        String[] email,
        String address,
        Date birthDate,
        Timestamp registrationDate,
        boolean active,
        int loginCount,
        double accountBalance,
        Long[] deviceIds
) implements UserDetails {
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
