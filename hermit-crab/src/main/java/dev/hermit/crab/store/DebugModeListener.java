package dev.hermit.crab.store;

import java.util.List;

/** Called after a developer-mode flip has been committed. {@code deletedTraceIds} is empty when opening. */
@FunctionalInterface
public interface DebugModeListener {
    void changed(boolean enabled, List<String> deletedTraceIds);
}
