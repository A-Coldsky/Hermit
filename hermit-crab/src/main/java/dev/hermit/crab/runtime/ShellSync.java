package dev.hermit.crab.runtime;

/**
 * Slice 2d owns startup and interval sync. The wiring slice attaches an implementation;
 * this slice does not provide one.
 */
public interface ShellSync extends AutoCloseable {
    void start();

    void syncNow();

    @Override
    void close();
}
