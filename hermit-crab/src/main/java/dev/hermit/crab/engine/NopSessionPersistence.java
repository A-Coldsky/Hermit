package dev.hermit.crab.engine;

import java.util.List;

public final class NopSessionPersistence implements SessionPersistence {

    @Override
    public void save(SessionKey key, List<StoredCookie> cookies) {
    }

    @Override
    public List<StoredCookie> load(SessionKey key) {
        return List.of();
    }

    @Override
    public void delete(SessionKey key) {
    }
}
