package com.wavjaby.db;

import com.wavjaby.persistence.Count;
import com.wavjaby.persistence.Select;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;


public interface UsersRepository extends UserDetailsService {
    User save(User user);

    @Override
    User loadUserByUsername(String username) throws UsernameNotFoundException;
    
    List<User> getUsers();
    
    @Count
    int count();
    
    @Select(field = "username")
    List<String> getUsernames();
    
    @Select(field = "userId")
    List<Long> getUserIds();

    @Select(field = "email")
    List<String[]> getEmails();

    @Select(field = "deviceIds")
    List<Long[]> getDeviceIds();
    
    @Select(field = "deviceIds")
    Long[] getDeviceIdByUserId(long userId);
}
