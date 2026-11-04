package dev.hermit.crab;

import dev.hermit.crab.cli.CrabCommand;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CrabMainTest {

    @Test
    void emptyArgsPrintsUsageWithoutExiting() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[0],
                name -> null,
                InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("crab serve").startsWith("用法：\n").doesNotContain("usage:");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
