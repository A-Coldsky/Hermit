package dev.hermit.crab.upstream;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.McpAuth;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;

/**
 * 上游 OAuth：控制台 PKCE 登录、令牌入库，以及调用前的刷新。
 * state 和 verifier 只放内存，10 分钟，用过即丢。令牌在 {@code oauth_token}。
 */
public final class McpOAuth implements AutoCloseable {

    public static final String NOT_OAUTH = "这个规则包不是 OAuth 登录。";
    public static final String DISCOVERY_FAILED = "无法发现上游的授权端点。";
    public static final String REJECTED = "上游拒绝了登录。请到控制台重新连接。";
    public static final String CALLBACK_OK = "已连接上游。可以关闭这个页面，回到控制台。";
    public static final String CALLBACK_EXPIRED = "登录已过期，请回到控制台重新连接。";
    public static final String CALLBACK_DENIED = "授权未完成。请回到控制台重新连接。";
    public static final String CALLBACK_FAILED = "没有换到令牌。请回到控制台重新连接。";
    public static final String RESERVED = "oauth_token 由控制台连接上游写入，不能手工填写。";
    public static final long START_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(15);
    static final Duration PENDING_TTL = Duration.ofMinutes(10);
    private static final int MAX_PENDING = 32;
    private static final String CALLBACK_PATH = "/console/oauth/callback";

    private final CredentialVault vault;
    private final SessionManager sessions;
    private final Consumer<SessionKey> discardUpstream;
    private final KnownSecrets knownSecrets;
    private final Clock clock;
    private final McpOAuthClient client;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

    public McpOAuth(
            CredentialVault vault,
            SessionManager sessions,
            Consumer<SessionKey> discardUpstream,
            KnownSecrets knownSecrets,
            Clock clock
    ) {
        this(vault, sessions, discardUpstream, knownSecrets, clock, new McpOAuthClient());
    }

    McpOAuth(
            CredentialVault vault,
            SessionManager sessions,
            Consumer<SessionKey> discardUpstream,
            KnownSecrets knownSecrets,
            Clock clock,
            McpOAuthClient client
    ) {
        this.vault = vault;
        this.sessions = sessions;
        this.discardUpstream = discardUpstream;
        this.knownSecrets = knownSecrets == null ? KnownSecrets.empty() : knownSecrets;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.client = client;
    }

    public Start start(Origin origin, ConchDoc doc, String account, String redirectUri, long deadlineNanos) {
        if (!oauth(doc)) {
            return Start.fail(ErrorCode.NOT_CONFIGURED, NOT_OAUTH);
        }
        if (!validRedirect(redirectUri)) {
            return Start.fail(ErrorCode.NOT_CONFIGURED, McpForwarder.HOST_DENIED);
        }
        requireAccount(origin, doc.conch(), account);
        McpAuth auth = doc.upstream().auth();
        if (auth.clientId() == null || auth.clientId().isBlank()) {
            return Start.fail(ErrorCode.NOT_CONFIGURED, NOT_OAUTH);
        }
        McpOAuthClient.Resolved endpoints = client.resolve(McpOAuthClient.Target.of(doc), auth, deadlineNanos);
        if (!endpoints.ok()) {
            return Start.fail(endpoints.code(), endpoints.message());
        }
        String verifier = randomToken();
        String state = randomToken();
        remember(new Pending(
                origin,
                doc.conch(),
                account,
                verifier,
                redirectUri,
                endpoints.tokenEndpoint(),
                auth.clientId(),
                doc.upstream().url(),
                List.copyOf(doc.upstream().allowedHosts()),
                clock.instant().plus(PENDING_TTL)), state);
        List<Map.Entry<String, String>> query = new ArrayList<>();
        query.add(Map.entry("response_type", "code"));
        query.add(Map.entry("client_id", auth.clientId()));
        query.add(Map.entry("redirect_uri", redirectUri));
        query.add(Map.entry("state", state));
        query.add(Map.entry("code_challenge", s256(verifier)));
        query.add(Map.entry("code_challenge_method", "S256"));
        if (!auth.scopes().isEmpty()) {
            query.add(Map.entry("scope", String.join(" ", auth.scopes())));
        }
        query.add(Map.entry("resource", doc.upstream().url()));
        return Start.ok(McpOAuthClient.withQuery(endpoints.authorizationEndpoint(), query));
    }

    public Callback callback(String code, String state, String error) {
        Pending login = take(state);
        if (login == null) {
            return Callback.fail(CALLBACK_EXPIRED);
        }
        if (error != null && !error.isBlank()) {
            return Callback.fail(CALLBACK_DENIED);
        }
        if (!usableCode(code)) {
            return Callback.fail(CALLBACK_FAILED);
        }
        long deadline = System.nanoTime() + START_BUDGET_NANOS;
        Loaded loaded = load(login.origin, login.conch, login.account, Map.of());
        McpOAuthClient.Target target = new McpOAuthClient.Target(login.upstreamUrl, login.allowedHosts);
        McpOAuthClient.Exchange exchange = client.exchangeCode(
                target,
                login.tokenEndpoint,
                login.clientId,
                login.upstreamUrl,
                code,
                login.redirectUri,
                login.verifier,
                loaded.clientSecret,
                clock.instant(),
                deadline);
        if (!exchange.ok()) {
            return Callback.fail(CALLBACK_FAILED);
        }
        synchronized (lock(login.origin, login.conch, login.account)) {
            save(login.origin, login.conch, login.account, exchange.token());
        }
        return Callback.ok(exchange.token().expiresAt());
    }

    public Outcome disconnect(Origin origin, ConchDoc doc, String account) {
        if (!oauth(doc)) {
            return Outcome.fail(ErrorCode.NOT_CONFIGURED, NOT_OAUTH);
        }
        requireAccount(origin, doc.conch(), account);
        synchronized (lock(origin, doc.conch(), account)) {
            clearLocked(origin, doc.conch(), account);
        }
        return Outcome.success();
    }

    public Status status(Origin origin, ConchDoc doc, String account) {
        if (!oauth(doc)) {
            return Status.notApplicable();
        }
        McpOAuthToken token = load(origin, doc.conch(), account, Map.of()).token;
        if (token == null) {
            return Status.disconnected();
        }
        return new Status(true, true, token.expiresAt());
    }

    /** 没有可用的访问令牌时返回 null。过期但仍能刷新的令牌会返回，调用方再 {@link #ensureFresh}。 */
    McpOAuthToken peek(Origin origin, String conch, String account, Map<String, String> credentials) {
        return load(origin, conch, account, credentials).token;
    }

    Attempt ensureFresh(
            Origin origin,
            String conch,
            String account,
            ConchDoc doc,
            Map<String, String> credentials,
            long deadlineNanos
    ) {
        synchronized (lock(origin, conch, account)) {
            Loaded loaded = load(origin, conch, account, credentials);
            if (loaded.token == null) {
                return Attempt.fail(ErrorCode.NOT_CONFIGURED, McpForwarder.OAUTH_NOT_CONNECTED);
            }
            if (!loaded.token.expiring(clock.instant())) {
                remember(loaded.token);
                return Attempt.ok(loaded.token);
            }
            return refreshLocked(origin, conch, account, doc, loaded, null, deadlineNanos);
        }
    }

    Attempt refreshAfterUnauthorized(
            Origin origin,
            String conch,
            String account,
            ConchDoc doc,
            Map<String, String> credentials,
            String rejectedAccess,
            long deadlineNanos
    ) {
        synchronized (lock(origin, conch, account)) {
            Loaded loaded = load(origin, conch, account, credentials);
            if (loaded.token == null) {
                return Attempt.fail(ErrorCode.NOT_CONFIGURED, McpForwarder.OAUTH_NOT_CONNECTED);
            }
            if (rejectedAccess != null && !rejectedAccess.equals(loaded.token.accessToken())) {
                remember(loaded.token);
                return Attempt.ok(loaded.token);
            }
            return refreshLocked(origin, conch, account, doc, loaded, loaded.token.accessToken(), deadlineNanos);
        }
    }

    public void clear(Origin origin, String conch, String account) {
        synchronized (lock(origin, conch, account)) {
            clearLocked(origin, conch, account);
        }
    }

    public static byte[] page(Callback callback) {
        String expiry = "";
        if (callback.connected && callback.expiresAt != null) {
            expiry = "<p>到期时间：" + DateTimeFormatter.ISO_INSTANT.format(callback.expiresAt) + "</p>";
        }
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><meta name="referrer" content="no-referrer"><title>连接上游</title></head>
                <body><p>%s</p>%s</body>
                </html>
                """.formatted(callback.message, expiry);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        client.close();
    }

    private Attempt refreshLocked(
            Origin origin,
            String conch,
            String account,
            ConchDoc doc,
            Loaded loaded,
            String rejectedAccess,
            long deadlineNanos
    ) {
        McpOAuthToken token = loaded.token;
        if (token.refreshToken() == null || token.refreshToken().isBlank()) {
            boolean expired = token.expiresAt() != null && !token.expiresAt().isAfter(clock.instant());
            if (rejectedAccess != null || expired) {
                clearLocked(origin, conch, account);
                return Attempt.fail(ErrorCode.AUTH_FAILED, REJECTED);
            }
            remember(token);
            return Attempt.ok(token);
        }
        if (doc == null || doc.upstream() == null || doc.upstream().auth() == null) {
            return Attempt.fail(ErrorCode.NOT_CONFIGURED, NOT_OAUTH);
        }
        McpOAuthClient.Resolved endpoints = client.resolve(
                McpOAuthClient.Target.of(doc), doc.upstream().auth(), deadlineNanos);
        if (!endpoints.ok()) {
            return Attempt.fail(endpoints.code(), endpoints.message());
        }
        McpOAuthClient.Exchange exchange = client.refresh(
                McpOAuthClient.Target.of(doc),
                endpoints.tokenEndpoint(),
                doc.upstream().auth().clientId(),
                doc.upstream().url(),
                token.refreshToken(),
                loaded.clientSecret,
                clock.instant(),
                deadlineNanos);
        if (!exchange.ok()) {
            return failedExchange(origin, conch, account, exchange.kind());
        }
        save(origin, conch, account, exchange.token());
        return Attempt.ok(exchange.token());
    }

    private Attempt failedExchange(Origin origin, String conch, String account, McpOAuthClient.ExchangeKind kind) {
        return switch (kind) {
            case HOST -> Attempt.fail(ErrorCode.NOT_CONFIGURED, McpForwarder.HOST_DENIED);
            case ADDRESS -> Attempt.fail(ErrorCode.UPSTREAM_ERROR, McpForwarder.ADDRESS_DENIED);
            case TIMEOUT -> Attempt.fail(ErrorCode.TIMEOUT, McpForwarder.DEADLINE);
            case CONNECT -> Attempt.fail(ErrorCode.UPSTREAM_ERROR, McpForwarder.CONNECT_FAILED);
            case REJECTED, TOKEN -> {
                clearLocked(origin, conch, account);
                yield Attempt.fail(ErrorCode.AUTH_FAILED, REJECTED);
            }
        };
    }

    private void save(Origin origin, String conch, String account, McpOAuthToken token) {
        if (vault != null) {
            vault.write(origin, conch, account, Map.of(McpOAuthToken.FIELD, token.json()), Actors.CONSOLE);
        }
        sessions.invalidate(new SessionKey(origin, conch, account));
        remember(token);
    }

    private void clearLocked(Origin origin, String conch, String account) {
        if (vault != null) {
            try {
                if (vault.fieldNames(origin, conch, account).contains(McpOAuthToken.FIELD)) {
                    vault.write(origin, conch, account, Map.of(McpOAuthToken.FIELD, ""), Actors.CONSOLE);
                }
            } catch (AccountNotFoundException ex) {
                // 账号已经删了，库里的行会一起走。
            }
        }
        sessions.invalidate(new SessionKey(origin, conch, account));
        if (discardUpstream != null) {
            discardUpstream.accept(new SessionKey(origin, conch, account));
        }
    }

    private void requireAccount(Origin origin, String conch, String account) {
        if (vault == null) {
            return;
        }
        vault.fieldNames(origin, conch, account);
    }

    private Loaded load(Origin origin, String conch, String account, Map<String, String> fallback) {
        Map<String, String> fields = fallback == null ? Map.of() : fallback;
        if (vault != null) {
            try {
                fields = vault.decryptForExecution(origin, conch, account);
            } catch (CredentialDecryptException ex) {
                return new Loaded(null, null);
            } catch (AccountNotFoundException ex) {
                return new Loaded(null, null);
            }
        }
        String secret = fields.get(McpOAuthToken.CLIENT_SECRET);
        if (secret != null && secret.isBlank()) {
            secret = null;
        }
        return new Loaded(McpOAuthToken.parse(fields.get(McpOAuthToken.FIELD)), secret);
    }

    private void remember(McpOAuthToken token) {
        if (token == null) {
            return;
        }
        knownSecrets.add(token.accessToken());
        knownSecrets.add(token.refreshToken());
    }

    private void remember(Pending login, String state) {
        Instant now = clock.instant();
        pending.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
        while (pending.size() >= MAX_PENDING) {
            String oldest = null;
            Instant oldestAt = null;
            for (Map.Entry<String, Pending> entry : pending.entrySet()) {
                if (oldestAt == null || entry.getValue().expiresAt.isBefore(oldestAt)) {
                    oldest = entry.getKey();
                    oldestAt = entry.getValue().expiresAt;
                }
            }
            if (oldest == null || pending.remove(oldest) == null) {
                break;
            }
        }
        pending.put(state, login);
    }

    private Pending take(String state) {
        if (state == null || state.isBlank() || state.length() > 200) {
            return null;
        }
        Pending login = pending.remove(state);
        if (login == null || !clock.instant().isBefore(login.expiresAt)) {
            return null;
        }
        return login;
    }

    private Object lock(Origin origin, String conch, String account) {
        return refreshLocks.computeIfAbsent(origin.name() + "\0" + conch + "\0" + account, key -> new Object());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean oauth(ConchDoc doc) {
        return doc != null && doc.upstream() != null && doc.upstream().auth() != null && doc.upstream().auth().oauth();
    }

    static boolean validRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return false;
        }
        McpAddresses.ParseResult parsed = McpAddresses.parse(redirect);
        if (!parsed.ok() || !"http".equals(parsed.url().scheme())) {
            return false;
        }
        String host = parsed.url().host();
        if (!"localhost".equals(host) && !McpAddressGuard.packageAllowsLoopback(host)) {
            return false;
        }
        try {
            return CALLBACK_PATH.equals(URI.create(redirect).getPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean usableCode(String code) {
        if (code == null || code.isBlank() || code.length() > 2048) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record Start(String authorizationUrl, ErrorCode code, String message) {
        public boolean ok() {
            return authorizationUrl != null && code == null;
        }

        static Start ok(String authorizationUrl) {
            return new Start(authorizationUrl, null, null);
        }

        static Start fail(ErrorCode code, String message) {
            return new Start(null, code, message);
        }
    }

    public record Callback(boolean connected, Instant expiresAt, String message) {
        public static Callback ok(Instant expiresAt) {
            return new Callback(true, expiresAt, CALLBACK_OK);
        }

        public static Callback fail(String message) {
            return new Callback(false, null, message);
        }
    }

    public record Outcome(ErrorCode code, String message) {
        public boolean ok() {
            return code == null;
        }

        static Outcome success() {
            return new Outcome(null, null);
        }

        static Outcome fail(ErrorCode code, String message) {
            return new Outcome(code, message);
        }
    }

    public record Status(boolean applicable, boolean connected, Instant expiresAt) {
        static Status notApplicable() {
            return new Status(false, false, null);
        }

        static Status disconnected() {
            return new Status(true, false, null);
        }
    }

    record Attempt(McpOAuthToken token, ErrorCode code, String message) {
        boolean ok() {
            return code == null && token != null;
        }

        static Attempt ok(McpOAuthToken token) {
            return new Attempt(token, null, null);
        }

        static Attempt fail(ErrorCode code, String message) {
            return new Attempt(null, code, message);
        }
    }

    private record Loaded(McpOAuthToken token, String clientSecret) {
    }

    private record Pending(
            Origin origin,
            String conch,
            String account,
            String verifier,
            String redirectUri,
            String tokenEndpoint,
            String clientId,
            String upstreamUrl,
            List<String> allowedHosts,
            Instant expiresAt
    ) {
    }
}
