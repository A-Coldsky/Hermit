package dev.hermit.crab.sync;

import java.time.Instant;

import javax.sql.DataSource;

/**
 * Records that an official conch version completed a call. Version retention keeps the latest
 * successful version. Rule mismatches are reported and do not switch the installed version.
 */
public final class VersionSuccess {
    private VersionSuccess() {
    }

    public static void record(DataSource dataSource, String conch, String version, Instant at) {
        if (dataSource == null || at == null || !ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(version)) {
            return;
        }
        new SyncStore(dataSource).recordSuccess(conch, version, at);
    }
}
