package dev.hermit.crab.runtime;

import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.store.Origin;

/**
 * Slice 2d watches rule mismatches. {@code observe} arguments mirror {@link ReportQueue#record}
 * because the contract does not fix the parameter list.
 */
public interface RollbackGuard {
    void observe(ExecutionResult result, Origin origin, String shell, String action, String account);
}
