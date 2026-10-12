package dev.hermit.reef.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MemoryLogAppender extends AppenderBase<ILoggingEvent> {

    public static final List<String> LINES = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        StringBuilder line = new StringBuilder();
        line.append(event.getLevel()).append(' ')
                .append(event.getLoggerName()).append(' ')
                .append(event.getFormattedMessage());
        if (event.getThrowableProxy() != null) {
            line.append(' ').append(event.getThrowableProxy().getClassName());
            line.append(' ').append(event.getThrowableProxy().getMessage());
        }
        LINES.add(line.toString());
    }
}
