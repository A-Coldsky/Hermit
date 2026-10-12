package dev.hermit.crab.sync;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class VersionRetention {

    static final int RECENT_VERSIONS = 3;

    private VersionRetention() {
    }

    /**
     * Keeps the three most recently installed versions, the version now current, the previous
     * successful version, and the version current was switched away from.
     */
    static Set<String> keep(
            String current,
            Optional<String> previousSuccess,
            Optional<String> switchedFrom,
            List<SyncStore.Installed> installed
    ) {
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        if (current != null) {
            keep.add(current);
        }
        previousSuccess.ifPresent(keep::add);
        switchedFrom.ifPresent(keep::add);
        int seen = 0;
        for (SyncStore.Installed row : installed) {
            if (seen == RECENT_VERSIONS) {
                break;
            }
            keep.add(row.version());
            seen++;
        }
        return keep;
    }
}
