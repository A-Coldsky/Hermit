package dev.hermit.crab.tls;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 最近的 TLS 失败，以及最近一小时内实际见过的 leaf。只在内存里。
 * 每个 host:port 保留一条失败，一共最多 32 条。
 */
final class TlsMemory {

    static final int MAX_FAILURES = 32;
    static final Duration SEEN_FOR = Duration.ofHours(1);

    private static final AtomicReference<TlsMemory> CURRENT = new AtomicReference<>();

    private final Clock clock;
    private final Object lock = new Object();
    private final LinkedHashMap<String, Failure> failures = new LinkedHashMap<>();
    private final LinkedHashMap<String, Seen> seen = new LinkedHashMap<>();

    TlsMemory(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    static void install(TlsMemory memory) {
        CURRENT.set(memory);
    }

    static void clear(TlsMemory memory) {
        if (memory != null) {
            CURRENT.compareAndSet(memory, null);
        }
    }

    static void note(Throwable failure, TransportFailure.View view) {
        TlsMemory memory = CURRENT.get();
        if (memory != null) {
            memory.record(failure, view);
        }
    }

    void record(Throwable failure, TransportFailure.View view) {
        if (view == null || view.kind() == null || !view.kind().name().startsWith("TLS_")) {
            return;
        }
        String host = view.host();
        int port = view.port();
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return;
        }
        HandshakeRecord record = HandshakeRecord.find(failure);
        List<TrustCert> chain = record == null ? List.of() : record.chain();
        String key = key(host, port);
        synchronized (lock) {
            remember(key, chain);
            failures.remove(key);
            failures.put(key, new Failure(
                    keyHost(host),
                    port,
                    view.kind().name(),
                    view.message() == null ? "" : view.message(),
                    clock.instant(),
                    chain));
            while (failures.size() > MAX_FAILURES) {
                failures.remove(failures.keySet().iterator().next());
            }
        }
    }

    void rememberProbe(String host, int port, List<TrustCert> chain) {
        synchronized (lock) {
            remember(key(host, port), chain);
        }
    }

    /**
     * 指纹必须和这个地址最近一小时内见过的 leaf 一致。过期的记录当作没见过。
     */
    TrustCert seenLeaf(String host, int port, String sha256, Instant now) {
        if (sha256 == null) {
            return null;
        }
        synchronized (lock) {
            Seen item = seen.get(key(host, port));
            if (item == null || now.isAfter(item.at().plus(SEEN_FOR))) {
                return null;
            }
            if (!item.leaf().sha256().equalsIgnoreCase(sha256.strip())) {
                return null;
            }
            return item.leaf();
        }
    }

    List<Failure> failures() {
        synchronized (lock) {
            ArrayList<Failure> rows = new ArrayList<>(failures.values());
            Collections.reverse(rows);
            return List.copyOf(rows);
        }
    }

    private void remember(String key, List<TrustCert> chain) {
        TrustCert leaf = leaf(chain);
        if (leaf == null || leaf.sha256() == null || leaf.sha256().isBlank()) {
            return;
        }
        seen.remove(key);
        seen.put(key, new Seen(leaf, clock.instant()));
        while (seen.size() > 256) {
            seen.remove(seen.keySet().iterator().next());
        }
    }

    private static TrustCert leaf(List<TrustCert> chain) {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        for (TrustCert certificate : chain) {
            if (certificate != null && !certificate.ca()) {
                return certificate;
            }
        }
        return chain.get(0);
    }

    private static String key(String host, int port) {
        return keyHost(host) + ":" + port;
    }

    private static String keyHost(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }

    record Failure(
            String host,
            int port,
            String kind,
            String message,
            Instant at,
            List<TrustCert> chain
    ) {
    }

    private record Seen(TrustCert leaf, Instant at) {
    }
}
