package dev.hermit.reef.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ReefPropertiesTest {

    private static final String CONFIGURED = "configured-agent-token";

    @Test
    void blankTokenWarnsWithoutWritingTheValue() {
        assertThat(warningTexts("")).containsExactly(ReefProperties.BLANK_AGENT_TOKEN);
        assertThat(warningTexts(null)).containsExactly(ReefProperties.BLANK_AGENT_TOKEN);
        List<String> whitespace = warningTexts("   ");
        assertThat(whitespace).containsExactly(ReefProperties.BLANK_AGENT_TOKEN);
        assertThat(whitespace.get(0)).doesNotContain("   ");
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
