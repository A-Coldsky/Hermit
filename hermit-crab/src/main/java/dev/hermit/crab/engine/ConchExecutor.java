package dev.hermit.crab.engine;

import dev.hermit.common.model.ConchDoc;

/**
 * 一次已经授权的调用。{@code authorized} 非空时必须用这份快照，不能再读注册表里的另一版本（审计 A01）。
 */
public interface ConchExecutor {

    ExecutionResult execute(ExecutionRequest request, ConchDoc authorized);
}
