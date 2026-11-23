package dev.hermit.crab.store;

import java.time.Instant;
import java.util.List;

import dev.hermit.crab.engine.SessionKey;

/** 一个身份和它的成员。成员按来源、包名、账号排序。 */
public record Identity(String id, String name, Instant createdAt, List<SessionKey> members) {
    public Identity {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
