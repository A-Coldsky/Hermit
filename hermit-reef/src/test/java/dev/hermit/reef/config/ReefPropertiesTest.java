package dev.hermit.reef.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReefPropertiesTest {

    private static final String CONFIGURED = "configured-agent-token";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableReef.class);

    @EnableConfigurationProperties(ReefProperties.class)
    static class EnableReef {
    }

    @Test
    void blankTokenDoesNotWarnFromPropertiesOrEchoTheValue() {
        assertThat(warningTexts("")).isEmpty();
        assertThat(warningTexts(null)).isEmpty();
        List<String> messages = capture("   ").stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(message -> message.contains("   "));
        assertThat(warningTexts("   ")).isEmpty();
    }

    @Test
    void configuredTokenDoesNotWarnOrAppearInTheLog() {
        List<ILoggingEvent> events = capture(CONFIGURED);
        assertThat(events).noneMatch(event -> event.getLevel() == Level.WARN);
        assertThat(events).noneMatch(event -> event.getFormattedMessage().contains(CONFIGURED));
    }

    @Test
    void blankTokenIsUnsetAndARealTokenIsKept() {
        ReefProperties blank = new ReefProperties();
        blank.setAgentToken("   ");
        assertThat(blank.configuredAgentToken()).isNull();

        ReefProperties configured = new ReefProperties();
        configured.setAgentToken(CONFIGURED);
        assertThat(configured.configuredAgentToken()).isEqualTo(CONFIGURED);
    }

    @Test
    void retentionCronDefaultsToThreeUtcAndRejectsBlankOrInvalidExpressions() {
        ReefProperties defaults = new ReefProperties();
        defaults.validate();
        assertThat(defaults.getRetentionCron()).isEqualTo("0 0 3 * * *");

        assertThatThrownBy(() -> {
            ReefProperties blank = new ReefProperties();
            blank.setRetentionCron("   ");
            blank.validate();
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hermit.reef.retention-cron");

        runner.withPropertyValues("hermit.reef.retention-cron=0 3 * * *").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("hermit.reef.retention-cron");
        });
        runner.withPropertyValues("hermit.reef.retention-cron=0 30 4 * * *").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ReefProperties.class).getRetentionCron()).isEqualTo("0 30 4 * * *");
        });
    }

    private static List<String> warningTexts(String token) {
        return capture(token).stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static List<ILoggingEvent> capture(String token) {
        Logger logger = (Logger) LoggerFactory.getLogger(ReefProperties.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReefProperties properties = new ReefProperties();
            properties.setAgentToken(token);
            properties.validate();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
