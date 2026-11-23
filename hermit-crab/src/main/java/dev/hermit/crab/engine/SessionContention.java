package dev.hermit.crab.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 同一网站的两个规则包是否在互相顶掉登录。只在内存里，重启清零。
 *
 * <p>只记一种事件：已经登录的会话在能力请求上收到 401、403 或被跳回登录页，
 * 并且它上次登录之后，同网站的另一个规则包登录过。首次登录、空闲超时后探活失败都不记。
 * 两个包在 {@link #WINDOW} 内各自被这样顶掉 {@link #ROUNDS} 次、且交替出现，才算一对告警。
 * Crab 分不清两边是不是同一个人，所以这只是「可能」。
 */
public final class SessionContention {

    public static final Duration WINDOW = Duration.ofMinutes(10);
    public static final int ROUNDS = 3;
    /** 最近一次达到阈值之后，告警保留这么久。 */
    public static final Duration KEEP = Duration.ofHours(24);
    private static final int MAX_EVENTS_PER_SITE = 512;

    /** 一对告警。{@code first} 按字典序在前。 */
    public record Pair(String site, String first, String second, Instant lastAt) {
    }

    private record Kick(Instant at, String conch, Set<String> after) {
    }

    private final Clock clock;
    /** 每个站点上各会话最近一次登录的先后序号。用序号不用时间：同一毫秒里的两次登录也要分出先后。 */
    private final Map<String, Map<SessionKey, Long>> logins = new HashMap<>();
    private long sequence;
    private final Map<String, ArrayDeque<Kick>> kicks = new HashMap<>();
    private final Map<List<String>, Instant> alerts = new LinkedHashMap<>();

    public SessionContention(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 登录成功。 */
    public synchronized void loggedIn(String site, SessionKey key) {
        if (site == null || key == null) {
            return;
        }
        logins.computeIfAbsent(site, ignored -> new HashMap<>()).put(key, ++sequence);
    }

    /** 已登录的会话在能力请求上失效。 */
    public synchronized void lost(String site, SessionKey key) {
        if (site == null || key == null) {
            return;
        }
        Map<SessionKey, Long> known = logins.get(site);
        if (known == null) {
            return;
        }
        // 重启前登录的会话没有记录，按最早处理：重启后别的包登录过就算。
        long mine = known.getOrDefault(key, 0L);
        TreeSet<String> after = new TreeSet<>();
        for (Map.Entry<SessionKey, Long> entry : known.entrySet()) {
            String other = entry.getKey().conch();
            if (!other.equals(key.conch()) && entry.getValue() > mine) {
                after.add(other);
            }
        }
        if (after.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        ArrayDeque<Kick> events = kicks.computeIfAbsent(site, ignored -> new ArrayDeque<>());
        events.addLast(new Kick(now, key.conch(), Set.copyOf(after)));
        prune(events, now);
        for (String other : after) {
            if (rounds(events, key.conch(), other) >= ROUNDS) {
                alerts.put(pairKey(site, key.conch(), other), now);
            }
        }
    }

    /** 还在保留期内的告警，最近的在前。 */
    public synchronized List<Pair> active() {
        Instant now = clock.instant();
        ArrayList<Pair> pairs = new ArrayList<>();
        Iterator<Map.Entry<List<String>, Instant>> each = alerts.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<List<String>, Instant> entry = each.next();
            if (Duration.between(entry.getValue(), now).compareTo(KEEP) > 0) {
                each.remove();
                continue;
            }
            List<String> key = entry.getKey();
            pairs.add(new Pair(key.get(0), key.get(1), key.get(2), entry.getValue()));
        }
        pairs.sort((left, right) -> right.lastAt().compareTo(left.lastAt()));
        return List.copyOf(pairs);
    }

    /** 两个包都被对方顶掉、交替出现的轮数。连续几次同一个包被顶掉只算一次。 */
    private static int rounds(ArrayDeque<Kick> events, String left, String right) {
        int runs = 0;
        String last = null;
        for (Kick kick : events) {
            boolean mine = kick.conch().equals(left) && kick.after().contains(right);
            boolean theirs = kick.conch().equals(right) && kick.after().contains(left);
            if (!mine && !theirs) {
                continue;
            }
            if (!kick.conch().equals(last)) {
                runs++;
                last = kick.conch();
            }
        }
        return runs / 2;
    }

    private static void prune(ArrayDeque<Kick> events, Instant now) {
        while (!events.isEmpty()
                && (events.size() > MAX_EVENTS_PER_SITE
                        || Duration.between(events.peekFirst().at(), now).compareTo(WINDOW) > 0)) {
            events.removeFirst();
        }
    }

    private static List<String> pairKey(String site, String left, String right) {
        return left.compareTo(right) <= 0 ? List.of(site, left, right) : List.of(site, right, left);
    }
}
