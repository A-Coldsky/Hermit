package dev.hermit.crab.config;

/** A global {@code config.yml} could not be loaded. The message names the key path and not the value. */
public final class ConfigFileException extends RuntimeException {
    public ConfigFileException(String message) {
        super(message);
    }
}
