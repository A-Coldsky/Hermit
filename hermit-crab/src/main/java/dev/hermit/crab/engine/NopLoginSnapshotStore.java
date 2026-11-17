package dev.hermit.crab.engine;

import java.util.Map;

/** 测试和未接数据库的会话管理器。保存被丢掉，读取始终为空。 */
public final class NopLoginSnapshotStore implements LoginSnapshotStore {

    @Override
    public void save(SessionKey key, Map<String, String> snapshot) {
    }

    @Override
    public Map<String, String> load(SessionKey key) {
        return Map.of();
    }

    @Override
    public void delete(SessionKey key) {
    }
}
