package dev.hermit.reef.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hermit.reef.testsupport.TestDataDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DataDirectoryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DataDirectoryConfiguration.class);

    @Test
    void emptyDataDirFailsBeforeTheWorkingDirectoryIsUsed() {
        Path cwd = Path.of("").toAbsolutePath();
        boolean dbExisted = Files.exists(cwd.resolve("db"));
        boolean conchesExisted = Files.exists(cwd.resolve("conches"));
        boolean keysExisted = Files.exists(cwd.resolve("keys"));
        boolean defaultExisted = Files.exists(cwd.resolve("reef-data"));

        DataDirectoryConfiguration.DataDirectoryInitializer initializer =
                new DataDirectoryConfiguration.DataDirectoryInitializer();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("hermit.reef.data-dir", "");
        initializer.setEnvironment(environment);
        assertThatThrownBy(() -> initializer.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ReefProperties.BLANK_DATA_DIR_MESSAGE)
                .hasMessageNotContaining("/app")
                .hasMessageNotContaining(cwd.toString());

        runner.withPropertyValues("hermit.reef.data-dir=").run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(messages(failure))
                    .contains(ReefProperties.BLANK_DATA_DIR_MESSAGE)
                    .doesNotContain("/app")
                    .doesNotContain(cwd.toString());
            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(ReefProperties.BLANK_DATA_DIR_MESSAGE);
        });

        assertThat(Files.exists(cwd.resolve("db"))).isEqualTo(dbExisted);
        assertThat(Files.exists(cwd.resolve("conches"))).isEqualTo(conchesExisted);
        assertThat(Files.exists(cwd.resolve("keys"))).isEqualTo(keysExisted);
        assertThat(Files.exists(cwd.resolve("reef-data"))).isEqualTo(defaultExisted);
    }

    @Test
    void whitespaceDataDirFailsTheSameWay() {
        DataDirectoryConfiguration.DataDirectoryInitializer initializer =
                new DataDirectoryConfiguration.DataDirectoryInitializer();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("hermit.reef.data-dir", "   ");
        initializer.setEnvironment(environment);
        assertThatThrownBy(() -> initializer.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ReefProperties.BLANK_DATA_DIR_MESSAGE);
    }

    @Test
    void configuredDirectoryIsCreated() {
        Path dir = TestDataDirs.create("data-dir-config");
        runner.withPropertyValues("hermit.reef.data-dir=" + dir).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(dir.resolve("db")).isDirectory();
            assertThat(dir.resolve("conches")).isDirectory();
            assertThat(dir.resolve("keys")).isDirectory();
        });
    }

    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = new HashSet<>();
        while (failure != null && seen.add(failure)) {
            text.append(String.valueOf(failure.getMessage())).append('\n');
            failure = failure.getCause();
        }
        return text.toString();
    }
}
