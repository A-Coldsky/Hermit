package dev.hermit.crab.runtime;

import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.store.Origin;

/**
 * Slice 2d records metrics. Not called until the wiring slice attaches an implementation.
 */
public interface ReportQueue {
    void record(ExecutionResult result, Origin origin, String shell, String action, String account);
}
