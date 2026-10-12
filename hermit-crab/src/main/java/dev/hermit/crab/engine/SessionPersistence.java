package dev.hermit.crab.engine;

import java.util.List;

/**
 * Cookie 是秘密。本切片只定义接口；加密落盘由调用方实现。
 * {@code load} / {@code save} / {@code delete} 不得回调 {@link SessionManager}。
 */
public interface SessionPersistence {

    void save(SessionKey key, List<StoredCookie> cookies);

    List<StoredCookie> load(SessionKey key);

    void delete(SessionKey key);
}
