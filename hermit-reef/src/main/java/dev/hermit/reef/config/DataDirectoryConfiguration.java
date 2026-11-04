package dev.hermit.reef.config;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DataDirectoryConfiguration {

    @Bean
    public static BeanFactoryPostProcessor dataDirectoryBeanFactoryPostProcessor() {
        return new DataDirectoryInitializer();
    }

    static final class DataDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            String configured = environment.getProperty("hermit.reef.data-dir", "./reef-data");
            // Path.of("") 是当前工作目录。空串必须在建目录之前失败，不能先落到工作目录再抛权限错误。
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException(ReefProperties.BLANK_DATA_DIR_MESSAGE);
            }
            try {
                DataDirectories.create(Path.of(configured));
            } catch (IOException ex) {
                throw new BeanInitializationException("Could not create data directories under " + configured, ex);
            }
        }
    }
}
