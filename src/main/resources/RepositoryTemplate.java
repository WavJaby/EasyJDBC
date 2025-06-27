%CLASS_PACKAGE_NAME%

import com.wavjaby.jdbc.util.FastResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
%CLASS_IMPORTS%

@Repository
public class %REPOSITORY_IMPL_NAME% implements %INTERFACE_CLASS_PATH% {
    private final JdbcTemplate jdbc;
    private final FastResultSetExtractor<%TABLE_DATA_CLASS%> tableMapper;

    %CLASS_FIELDS%
    public %REPOSITORY_IMPL_NAME%(JdbcTemplate jdbc %CLASS_FIELDS_PARAMETER%) {
        this.jdbc = jdbc;
        %CLASS_FIELDS_INIT%
//        jdbc.update(%SQL_TABLE_CREATE%);
        tableMapper = new FastResultSetExtractor<>(%TABLE_DATA_CLASS%.class, %SQL_TABLE_COLUMN_COUNT%);
//        tableMapper = new FastResultSetExtractor<>(%TABLE_DATA_CLASS%.class, "%TABLE_NAME%", jdbc);
    }
    %REPOSITORY_METHODS%
}
