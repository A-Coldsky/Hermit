package dev.hermit.reef.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(ReefTestConfig.class)
class DefaultDataSourceTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("default-datasource").toString());
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment environment;

    @Test
    void unsetDatasourceUrlKeepsTheH2FileDatabase() throws Exception {
        assertThat(environment.getProperty("spring.datasource.driver-class-name")).isNull();
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:file:");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:file:");
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
        }
    }

    @Test
    void mysqlUrlSelectsTheMysqlDriverWithoutPinningItInConfig() throws Exception {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yml).doesNotContain("driver-class-name");
        assertThat(DatabaseDriver.fromJdbcUrl("jdbc:mysql://mysql:3306/hermit").getDriverClassName())
                .isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(Class.forName("com.mysql.cj.jdbc.Driver")).isNotNull();
    }
}
