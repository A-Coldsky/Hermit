package dev.hermit.reef.conch;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 某个 Conch 还没有任何版本行时，{@code SELECT ... FOR UPDATE} 锁不住空结果。
 * 上传和丢弃未发布版本在进入事务之前按 Conch 名串行，补上这个窗口。发布不使用这把锁。
 */
@Component
class ConchVersionLock {

    private final ConcurrentHashMap<String, Object> monitors = new ConcurrentHashMap<>();

    Object monitor(String conch) {
        return monitors.computeIfAbsent(conch, ignored -> new Object());
    }
}
