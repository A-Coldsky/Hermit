package dev.hermit.crab.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.config.CrabConfigLoader;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.store.CrabPaths;

/**
 * Serve lifecycle. Installed conches are verified before HTTP listens. Sync and report loops start
 * after the port is open, and the first sync does not block the caller. Shutdown closes HTTP, then
 * stops the loops and flushes in-memory report counts.
 */
public final class CrabServer implements AutoCloseable {
    private final CrabRuntime runtime;
    private final CrabHttpServer http;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CrabServer(CrabRuntime runtime, CrabHttpServer http) {
        this.runtime = runtime;
        this.http = http;
    }

    public static CrabServer start(Function<String, String> environment) {
        CrabPaths paths = CrabPaths.fromEnvironment(environment);
        CrabConfig config = CrabConfigLoader.load(paths);
        CrabRuntime runtime = CrabRuntime.open(paths, config, environment);
        try {
            return start(runtime);
        } catch (RuntimeException ex) {
            runtime.close();
            throw ex;
        }
    }

    public static CrabServer start(CrabRuntime runtime) {
        runtime.loadInstalledConches();
        CrabHttpServer http = new CrabHttpServer(runtime);
        boolean started = false;
        try {
            http.start();
            started = true;
            runtime.startServing();
            return new CrabServer(runtime, http);
        } catch (RuntimeException ex) {
            runtime.abandonServing();
            if (started) {
                http.close();
            }
            throw ex;
        }
    }

    public CrabRuntime runtime() {
        return runtime;
    }

    public int port() {
        return http.port();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        http.close();
        runtime.close();
    }
}
