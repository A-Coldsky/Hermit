package dev.hermit.crab.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

/** Records the last successful call of each official version so retention can keep it. */
public final class VersionSuccess {
    /** 同一个版本连续成功时，隔这么久才再写一次库。 */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(60);

    private final DataSource dataSource;
    private final Clock clock;
    /** 每个规则包最近一次落库的成功。换版本立刻写，所以各版本之间的先后顺序不受节流影响。 */
    private final ConcurrentHashMap<String, Stamp> written = new ConcurrentHashMap<>();

    public VersionSuccess(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void record(String conch, String version) {
        if (!ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(version)) {
            return;
        }
        Instant now = clock.instant();
        Stamp last = written.get(conch);
        if (last != null && last.version().equals(version)
                && Duration.between(last.at(), now).abs().compareTo(MIN_INTERVAL) < 0) {
            return;
        }
        new SyncStore(dataSource).recordSuccess(conch, version, now);
        written.put(conch, new Stamp(version, now));
    }

    public static void record(DataSource dataSource, String conch, String version, Instant at) {
        if (dataSource == null || at == null || !ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(version)) {
            return;
        }
        new SyncStore(dataSource).recordSuccess(conch, version, at);
    }

    private record Stamp(String version, Instant at) {
    }
}
