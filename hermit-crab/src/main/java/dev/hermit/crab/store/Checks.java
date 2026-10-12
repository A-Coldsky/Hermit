package dev.hermit.crab.store;

import java.util.Objects;
import java.util.regex.Pattern;

final class Checks {
    private static final Pattern ACCOUNT_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private Checks() {
    }

    static Origin origin(Origin origin) {
        return Objects.requireNonNull(origin, "origin");
    }

    static String shell(String shell) {
        if (shell == null || shell.isBlank()) {
            throw new IllegalArgumentException("shell is required");
        }
        return shell;
    }

    static String account(String account) {
        if (account == null || !ACCOUNT_NAME.matcher(account).matches()) {
            throw new IllegalArgumentException("account name must match [A-Za-z0-9_-]{1,64}");
        }
        return account;
    }

    static String actor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        return actor;
    }

    static String field(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        return field;
    }

    static String configKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is required");
        }
        return key;
    }
}
