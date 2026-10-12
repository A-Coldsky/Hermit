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
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[0],
                name -> null,
                InputStream.nullInputStream(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("crab serve");
    }
}
