package dev.hermit.reef.shell;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 某个 Shell 还没有任何版本行时，{@code SELECT ... FOR UPDATE} 锁不住空结果。
 * 上传在进入事务之前按 Shell 名串行，补上这个窗口。发布和回滚不使用这把锁。
 */
@Component
class ShellVersionLock {

    private final ConcurrentHashMap<String, Object> monitors = new ConcurrentHashMap<>();

    Object monitor(String shell) {
        return monitors.computeIfAbsent(shell, ignored -> new Object());
    }
}
