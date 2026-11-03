package dev.hermit.crab.sync;

import java.time.Instant;
import java.util.List;

/**
 * Last successful Reef manifest plus this Crab's subscription and install flags.
 * {@code configured} is false when {@code reef.url} is unset; {@code conches} is then empty.
 */
public record ConchCatalog(boolean configured, Instant syncedAt, List<Entry> conches) {
    public ConchCatalog {
        conches = conches == null ? List.of() : List.copyOf(conches);
    }

    public record Entry(
            String conch,
            String version,
            String title,
            String description,
            int capabilityCount,
            boolean subscribed,
            boolean installed,
            String installedVersion,
            boolean disabled,
            boolean localConflict,
            boolean updateAvailable,
            String latestVersion,
            List<String> newRequiredCredentials
    ) {
        public Entry {
            newRequiredCredentials = newRequiredCredentials == null ? List.of() : List.copyOf(newRequiredCredentials);
        }
    }
}
