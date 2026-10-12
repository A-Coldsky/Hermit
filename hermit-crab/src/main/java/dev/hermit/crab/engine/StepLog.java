package dev.hermit.crab.engine;

/**
 * 步骤日志。路径不含查询串，也不含请求体、响应体、头部或 Cookie。
 */
public record StepLog(
        int index,
        String type,
        long durationMillis,
        String method,
        String path,
        Integer status,
        Integer extractCount
) {
}
