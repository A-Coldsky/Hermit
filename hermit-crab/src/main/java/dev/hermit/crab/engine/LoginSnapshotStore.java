package dev.hermit.crab.engine;

import java.util.Map;

/**
 * 一个账号的登录快照。空映射表示没有快照。实现不得写入 {@code sessions} 或 {@code config_audit}。
 */
public interface LoginSnapshotStore {

    void save(SessionKey key, Map<String, String> snapshot);

    Map<String, String> load(SessionKey key);

    void delete(SessionKey key);
}
