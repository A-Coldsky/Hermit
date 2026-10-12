package dev.hermit.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.validate.ShellValidator;
import dev.hermit.common.validate.ValidationResult;

public final class Fixtures {

    private Fixtures() {
    }

    public static String yaml(String name) {
        String path = "shells/" + name;
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static ShellDoc parse(String name) {
        return ShellParser.parse(yaml(name));
    }

    public static ValidationResult validate(String name) {
        return ShellValidator.validate(parse(name), ShellValidator.Context.of(null));
    }
}
