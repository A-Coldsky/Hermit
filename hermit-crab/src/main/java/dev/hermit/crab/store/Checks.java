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

    static String conch(String conch) {
        if (conch == null || conch.isBlank()) {
            throw new IllegalArgumentException("conch is required");
        }
        return conch;
    }

    static String account(String account) {
        if (account == null || !ACCOUNT_NAME.matcher(account).matches()) {
            throw new IllegalArgumentException("account name must match [A-Za-z0-9_-]{1,64}");
        }
        return account;
    }

    static String actor(String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor) && !Actors.SYNC.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli, console, or sync");
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
