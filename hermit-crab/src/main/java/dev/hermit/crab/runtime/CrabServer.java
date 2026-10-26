package dev.hermit.crab.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.config.CrabConfigLoader;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.store.CrabPaths;

/**
 * Serve lifecycle. Installed conches are verified before HTTP listens. Sync and report loops start
 * after the port is open, and the first sync does not block the caller. Shutdown stops report,
 * then sync, then HTTP, then the runtime.
 */
public final class CrabServer implements AutoCloseable {
    private final CrabRuntime runtime;
    private final CrabHttpServer http;
    private final ScheduledExecutorService syncScheduler;
    private final ScheduledExecutorService reportScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CrabServer(
            CrabRuntime runtime,
            CrabHttpServer http,
            ScheduledExecutorService syncScheduler,
            ScheduledExecutorService reportScheduler
    ) {
        this.runtime = runtime;
        this.http = http;
        this.syncScheduler = syncScheduler;
        this.reportScheduler = reportScheduler;
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
        ScheduledExecutorService syncScheduler = null;
        ScheduledExecutorService reportScheduler = null;
        boolean started = false;
        try {
            http.start();
            started = true;
            runtime.startBackground();
            if (runtime.conchSync() != null) {
                syncScheduler = scheduler("hermit-crab-sync");
                runtime.conchSync().start(syncScheduler);
                syncScheduler.execute(runtime::syncQuietly);
            }
            if (runtime.reportSender() != null) {
                reportScheduler = scheduler("hermit-crab-report");
                long seconds = runtime.config().reportIntervalSeconds();
                reportScheduler.scheduleWithFixedDelay(runtime::flushQuietly, seconds, seconds, TimeUnit.SECONDS);
            }
            return new CrabServer(runtime, http, syncScheduler, reportScheduler);
        } catch (RuntimeException ex) {
            stop(reportScheduler);
            stop(syncScheduler);
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
        stop(reportScheduler);
        stop(syncScheduler);
        http.close();
        runtime.close();
    }

    private static ScheduledExecutorService scheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void stop(ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
