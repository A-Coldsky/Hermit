package dev.hermit.reef;

import dev.hermit.reef.config.ReefProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(ReefProperties.class)
public class ReefApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReefApplication.class, args);
    }
}
