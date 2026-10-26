package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;

final class EngineTestSupport {

    static final String PASSWORD = "S3cret!pwd";
    static final String WRONG_PASSWORD = "Wrong-pass-9";

    private EngineTestSupport() {
    }

    static String legacyYaml() {
        try (InputStream in = EngineTestSupport.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 samples/legacy_oa.yaml");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static ConchDoc legacy() {
        return ConchParser.parse(legacyYaml());
    }

    static String actionConch(String name, String action, String steps, boolean constantOutput) {
        String output = constantOutput
                ? "value: { type: string, description: 结果, value: ok }"
                : "value: { type: string, description: 结果 }";
        String indented = steps.stripIndent().indent(6).stripTrailing();
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  %s:
                    title: 能力
                    description: 说明
                    output:
                      %s
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                %s
                """.formatted(name, action, output, indented);
    }

    static AccountContext account(String user, String password, String baseUrl) {
        return new MapAccount(
                Map.of("username", user, "password", password),
                Map.of("base_url", baseUrl));
    }

    static ExecutionRequest request(
            Origin origin,
            String conch,
            String action,
            String account,
            Map<String, Object> input,
            AccountContext context
    ) {
        return new ExecutionRequest("t-1", origin, conch, action, input, account, context);
    }

    static String flatten(Object value) {
        StringBuilder text = new StringBuilder();
        append(text, value);
        return text.toString();
    }

    static String flatten(ExecutionResult result) {
        StringBuilder text = new StringBuilder();
        text.append(result.success()).append('\n');
        text.append(result.conchVersion()).append('\n');
        text.append(result.traceId()).append('\n');
        text.append(result.durationMillis()).append('\n');
        append(text, result.data());
        if (result.error() != null) {
            text.append(result.error().code()).append('\n');
            text.append(result.error().message()).append('\n');
            text.append(result.error().summary()).append('\n');
            text.append(result.error().failedStepIndex()).append('\n');
            text.append(result.error().failedStepType()).append('\n');
        }
        for (StepLog log : result.stepLogs()) {
            text.append(log.index()).append(' ');
            text.append(log.type()).append(' ');
            text.append(log.method()).append(' ');
            text.append(log.path()).append(' ');
            text.append(log.status()).append(' ');
            text.append(log.extractCount()).append(' ');
            text.append(log.durationMillis()).append('\n');
        }
        return text.toString();
    }

    static void secretsAbsent(String text, String secret) {
        SecretChecks.absent(text, secret);
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        SecretChecks.absent(text, encoded);
        SecretChecks.absent(text, encoded.replace("+", "%20"));
    }

    private static void append(StringBuilder text, Object value) {
        if (value == null) {
            text.append("null\n");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                text.append(entry.getKey()).append('=');
                append(text, entry.getValue());
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                append(text, item);
            }
            return;
        }
        text.append(value).append('\n');
    }

    record MapAccount(Map<String, String> credentials, Map<String, String> config) implements AccountContext {
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-12T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class LogCapture implements AutoCloseable {
        private final Logger logger = Logger.getLogger("dev.hermit.crab.engine");
        private final Level previous = logger.getLevel();
        private final StringBuilder text = new StringBuilder();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (text) {
                    text.append(record.getLevel().getName()).append(' ');
                    text.append(record.getMessage()).append('\n');
                    Object[] params = record.getParameters();
                    if (params != null) {
                        for (Object param : params) {
                            text.append(param).append('\n');
                        }
                    }
                    if (record.getThrown() != null) {
                        StringWriter writer = new StringWriter();
                        record.getThrown().printStackTrace(new PrintWriter(writer));
                        text.append(writer);
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        LogCapture() {
            logger.setLevel(Level.ALL);
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        String text() {
            synchronized (text) {
                return text.toString();
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    static final class StreamCapture implements AutoCloseable {
        private final PrintStream out = System.out;
        private final PrintStream err = System.err;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamCapture() {
            System.setOut(new PrintStream(new Tee(out, buffer), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(err, buffer), true, StandardCharsets.UTF_8));
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static final class Tee extends OutputStream {
        private final OutputStream left;
        private final OutputStream right;

        private Tee(OutputStream left, OutputStream right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void write(int b) throws IOException {
            left.write(b);
            right.write(b);
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            left.write(bytes, off, len);
            right.write(bytes, off, len);
        }
    }
}
