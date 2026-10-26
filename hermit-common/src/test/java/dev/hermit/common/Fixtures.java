package dev.hermit.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationResult;

public final class Fixtures {

    private Fixtures() {
    }

    public static String yaml(String name) {
        String path = "conches/" + name;
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static ConchDoc parse(String name) {
        return ConchParser.parse(yaml(name));
    }

    public static ValidationResult validate(String name) {
        return ConchValidator.validate(parse(name), ConchValidator.Context.of(null));
    }
}
