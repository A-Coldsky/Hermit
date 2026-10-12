package dev.hermit.crab.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.ReportJson;

public final class ReportSender {

    private static final Logger LOG = Logger.getLogger(ReportSender.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ReportSettings settings;
    private final ReportQueue queue;
    private final Clock clock;
    private final HttpClient client;
    private Duration backoff = ReportSettings.INITIAL_BACKOFF;
    private Instant notBefore = Instant.EPOCH;

    public ReportSender(ReportSettings settings, ReportQueue queue) {
        this(settings, queue, Clock.systemUTC());
    }

    public ReportSender(ReportSettings settings, ReportQueue queue, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FlushResult flushOnce() {
        return send(false);
    }

    public FlushResult confirmAndSend() {
        return send(true);
    }

    public ReportPreview preview() {
        if (!settings.enabled()) {
            return new ReportPreview(emptyBatch(), queue.dropped());
        }
        ReportQueue.Pending pending = queue.peek();
        return new ReportPreview(pending.batch(), queue.dropped());
    }

    private FlushResult send(boolean force) {
        if (!settings.enabled()) {
            return FlushResult.skipped();
        }
        synchronized (this) {
            if (!force && clock.instant().isBefore(notBefore)) {
                return FlushResult.skipped();
            }
            ReportQueue.Pending pending = queue.peek();
            if (pending.isEmpty()) {
                return FlushResult.skipped();
            }
            int status;
            try {
                status = post(ReportJson.write(pending.batch()));
            } catch (IOException ex) {
                scheduleBackoff();
                LOG.log(Level.WARNING, "report flush failed");
                return FlushResult.rejected(0);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                scheduleBackoff();
                LOG.log(Level.WARNING, "report flush interrupted");
                return FlushResult.rejected(0);
            }
            if (status >= 200 && status < 300) {
                queue.ack(pending);
                backoff = ReportSettings.INITIAL_BACKOFF;
                notBefore = Instant.EPOCH;
                return FlushResult.accepted(status);
            }
            scheduleBackoff();
            LOG.log(Level.WARNING, "report flush rejected status={0}", status);
            return FlushResult.rejected(status);
        }
    }

    private int post(String json) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint("agent/reports"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Hermit-Instance", settings.instanceId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        settings.token().ifPresent(token -> builder.header("X-Hermit-Token", token));
        HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private void scheduleBackoff() {
        notBefore = clock.instant().plus(backoff);
        Duration doubled = backoff.multipliedBy(2);
        backoff = doubled.compareTo(ReportSettings.MAX_BACKOFF) > 0 ? ReportSettings.MAX_BACKOFF : doubled;
    }

    private ReportBatch emptyBatch() {
        return new ReportBatch(
                settings.instanceId().toString(),
                settings.crabVersion(),
                clock.instant(),
                List.of(),
                List.of(),
                List.of());
    }

    private URI endpoint(String path) {
        String text = settings.reefUrl().toString();
        if (!text.endsWith("/")) {
            text = text + "/";
        }
        return URI.create(text).resolve(path);
    }
}
