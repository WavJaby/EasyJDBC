# EasyJDBC

A high-performance, annotation-based JDBC framework for Java that generates repository implementations at compile time.
EasyJDBC provides a lightweight alternative to JPA with significantly better performance while maintaining familiar
annotation-driven development.

## 🚀 Features

- **High Performance**: Significantly faster than JPA for both insert and query operations
- **Compile-time Code Generation**: Repository implementations generated during compilation
- **Annotation-driven**: Familiar JPA-like annotations for easy adoption
- **Spring Boot Integration**: Seamless integration with Spring Boot and Spring Data
- **Type Safety**: Full compile-time type checking
- **Lightweight**: Minimal runtime overhead
- **Modern Java Support**: Built for Java 17+ with support for Records
- **Advanced Features**:
    - Custom WHERE clause conditions with `@QuerySQL`
    - Relationship mapping (`@JoinColumn`)
    - Automatic ID generation with Snowflake algorithm
    - Database schema and table auto-creation
    - Unique constraints and indexes
    - Virtual tables and inheritance

## 📊 Performance

Based on comprehensive performance tests comparing EasyJDBC with JPA:

### Test Configuration

| Parameter       | Value                         |
|-----------------|-------------------------------|
| **Dataset**     | 100,000 User + Device records |
| **Iterations**  | 10 iterations average         |
| **Query**       | query with 400 iterations     |
| **Database**    | H2 in-memory                  |
| **Environment** | Spring Boot 3.5.3, Java 17    |

### Performance Test Results

| Test Type  | Operation                  | EasyJDBC Avg | JPA Avg | Performance Gain |
|------------|----------------------------|--------------|---------|------------------|
| **Insert** | 100K User + Device records | ~1600ms      | ~2500ms | **1.5x faster**  |
| **Query**  | 400 iterations             | ~2257ms      | ~4421ms | **2.0x faster**  |

### Test Execution

Run the performance tests yourself:

```bash
./gradlew test --tests "*PerformanceTest*"
```

*Performance results may vary based on database configuration, hardware, and data complexity. Tests are conducted using
H2 in-memory database.*

## 🏗️ Architecture

EasyJDBC uses annotation processing to generate repository implementations at compile time:

1. **Entities**: Annotated with `@Table` and persistence annotations
2. **Repository Interfaces**: Define method signatures
3. **Code Generation**: Annotation processor generates implementations
4. **Runtime**: Generated repositories use optimized JDBC operations

## 📦 Installation

### Gradle Configuration

Add EasyJDBC to your `build.gradle.kts`:

```kotlin
dependencies {
    // Required Spring Boot dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")

    // EasyJDBC util
    compileOnly("com.wavjaby:easyjdbc:1.0-SNAPSHOT")
    // Annotation processing
    annotationProcessor("com.wavjaby:easyjdbc:1.0-SNAPSHOT")
}
```

## 🔧 Quick Start

### 1. Define Your Entity

```java
@Table(name = "USERS", repositoryClass = UsersRepository.class)
public record User(
        @Id
        @GenericGenerator(strategy = Snowflake.class)
        long userId,

        @Column(unique = true)
        String username,
        String password,

        String email,
        Timestamp registrationDate,
        boolean active
) {
}
```

### 2. Create Repository Interface

```java
import com.wavjaby.persistence.Count;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

public interface UsersRepository extends UserDetailsService {
    User addUser(User user);

    /**
     * Spring Security UserDetailsService
     * Match with username or email.
     */
    @Override
    User loadUserByUsername(@Where({"username", "email"}) String username);

    List<User> getUsers();
    
    List<User> findByNickname(@Where(ignoreCase = true, operation = "like") String username);
    
    User getById(long userId);

    boolean isUsernameExist(String username);

    @Modifying
    void updateUserPassword(@Where long id, @Where("password") String oldPassword, @FieldName("password") String newPassword);

    @Delete
    void deleteUser(long id);
    
    @Count
    int count();
}
```

### 3. Configure Spring Boot Application

```java

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.yourpackage",
        "com.wavjaby.jdbc.util"  // Include EasyJDBC utilities
})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public Snowflake snowflake() {
        return new Snowflake(1, 1704067200000L); // workerId, epochTimestamp
    }
}
```

### 4. Use in Your Service

```java

@Service
public class UserService {

    @Autowired
    private UsersRepository usersRepository;

    public User createUser(String username, String password) {
        User newUser = new User(
                -1, // ID will be auto-generated
                username,
                password,
                null, null, null,
                new Timestamp(System.currentTimeMillis()),
                true
        );
        return usersRepository.addUser(newUser);
    }

    public List<User> getAllUsers() {
        return usersRepository.getUsers();
    }

    public User getUserByUsername(String username) {
        return usersRepository.loadUserByUsername(username);
    }

    public int getUserCount() {
        return usersRepository.count();
    }
}
```

## 🔗 Relationships

### Many-to-One Relationship

```java
@Table(schema = "device", repositoryClass = DeviceRepository.class)
public record Device(
        @Id
        @GenericGenerator(strategy = Snowflake.class)
        long id,

        @JoinColumn(referencedClass = User.class, referencedClassFieldName = "userId")
        long ownerId,

        @NotNull
        @Column(name = "NAME_STR")
        String deviceName,

        @NotNull
        @Column(precision = 10, scale = 6)
        double numeric,

        String description
) {}
```

## 📝 Available Annotations

### Entity Annotations

- `@Table` - Mark entity class and specify repository
- `@Column` - Customize column mapping
- `@Id` - Mark primary key field
- `@NotNull` - Add NOT NULL constraint
- `@ColumnDefault` - Set default values
- `@UniqueConstraint` - Define unique constraints

### Query Annotations

- `@Select` - Custom select queries
- `@QuerySQL` - Custom WHERE clause conditions
- `@Where` - Specify WHERE conditions
- `@OrderBy` - Add ORDER BY clauses
- `@Limit` - Limit result count
- `@Count` - Count operations

### Modification Annotations

- `@Modifying` - Mark update operations
- `@Delete` - Mark delete operations
- `@UpdateData` - Specify update data

### Relationship Annotations

- `@JoinColumn` - Foreign key mapping

### Generation Annotations

- `@GenericGenerator` - Custom ID generation
- `@FieldName` - Custom field naming

## 🛠️ Advanced Features

### Custom WHERE Clause Conditions

```java
public interface FriendRepository {
    // Add custom WHERE conditions to queries
    @QuerySQL("ACCEPT IS NULL")
    List<Friend> getPendingRequests(long userId);

    @QuerySQL("ACCEPT=TRUE")
    boolean isFriend(@Where(value = {"userId", "friendId"}) long userIdA,
                     @Where(value = {"userId", "friendId"}) long userIdB);

    @QuerySQL("(ACCEPT IS NULL OR ACCEPT=FALSE)")
    List<Friend> getSentRequests(long userId);

    @QuerySQL("ACCEPT=TRUE")
    @Select(columnSql = """
            case when USER_ID = :userId
                then FRIEND_ID
                else USER_ID
            end as FRIEND_ID""")
    List<Long> getFriendIds(@Where(value = {"userId", "friendId"}) long userId);
}
```

### Virtual Tables

```java

@Table(
        repositoryClass = DeviceSummary.Repository.class,
        virtual = true,
        virtualBaseClass = User.class
)
public record DeviceSummary(
        @JoinColumn(referencedClass = Device.class, referencedClassFieldName = "ownerId")
        long userId,
        // Value from User
        String username,
        String email,
        // Value from Device
        int deviceName,
        int description
) {
    public interface Repository {
        DeviceSummary getDeviceSummaryByOwnerId(long userId);
    }
}
```

## 🔧 Configuration

### Database Configuration

EasyJDBC has been tested and verified to work with H2 database. Here's the recommended configuration:

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    username: User
    password: ""
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true  # Enable H2 console for development
```

**Note**: This library has only been tested with the H2 database. While it may work with other JDBC-compatible
databases, H2 is the only officially supported and tested database at this time.

## 🧪 Testing

EasyJDBC includes comprehensive performance tests. Run them with:

```bash
./gradlew test --tests "*PerformanceTest*"
```

## 📋 Requirements

- **Java**: 17 or higher
- **Spring Boot**: 3.0 or higher
- **Database**: H2 database (officially tested and supported)
- **Build Tool**: Gradle with annotation processing support

**Database Compatibility**:
While EasyJDBC may work with other JDBC-compatible databases, it has only been thoroughly tested with H2 database.
Support for other databases is not guaranteed.

## 🆚 EasyJDBC vs JPA

| Feature              | EasyJDBC           | JPA         |
|----------------------|--------------------|-------------|
| Performance          | ⚡ High             | 🐌 Moderate |
| Compile-time Safety  | ✅ Full             | ⚠️ Limited  |
| Learning Curve       | 📈 Easy (JPA-like) | 📈 Moderate |
| Runtime Overhead     | 🪶 Minimal         | 🏋️ Heavy   |
| Code Generation      | ✅ Compile-time     | ❌ Runtime   |
| Custom WHERE Clauses | ✅ Easy             | ⚠️ Complex  |
| Relationship Mapping | ⚠️ Limited         | ✅ Full      |
| Caching              | 🔧 Manual          | ✅ Built-in  |

**EasyJDBC** - High-performance JDBC made easy! 🚀
