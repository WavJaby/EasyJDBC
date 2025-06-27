package com.wavjaby.db;

import com.wavjaby.persistence.Count;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;


public interface UsersRepository extends UserDetailsService {
    User addUser(User user);

    @Override
    User loadUserByUsername(String username) throws UsernameNotFoundException;
    
    List<User> getUsers();
    
    @Count
    int count();
}
