%CLASS_PACKAGE_NAME%

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class %CLASS_NAME% implements ApplicationListener<ApplicationReadyEvent>{
    private static final Logger logger = LoggerFactory.getLogger(%CLASS_NAME%.class);
    private final JdbcTemplate jdbc;

    public %CLASS_NAME%(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initSchemeAndTable() {
        %INIT_TABLE%
    }
    
    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        logger.debug("Start init scheme and table");
        initSchemeAndTable();
        logger.debug("Init scheme and table success");
    }
}
