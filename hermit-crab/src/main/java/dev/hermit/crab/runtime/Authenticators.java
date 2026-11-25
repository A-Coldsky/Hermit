package dev.hermit.crab.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.validate.AuthenticatorChecks;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.engine.AuthLinks;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.SessionPersistence;
import dev.hermit.crab.engine.SharedSessions;
import dev.hermit.crab.engine.StoredCookie;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuthBinding;
import dev.hermit.crab.store.AuthBindingStore;
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;

/**
 * 授权器绑定：引用授权器的包（{@code auth.use}）的账号指向授权器（{@code driver: auth}）的一个账号。
 * 只有控制台能改。绑定时使用者确认这个包的站点地址；地址变了、授权器换了来源或不再满足引用条件时绑定作废。
 * 调用路径每次问 {@link #resolve}：条件不满足时返回 null，对外只说「没有绑定可用的登录授权」，不带授权器和账号的名字。
 */
public final class Authenticators implements AuthLinks {
    /** 一个账号能不能绑到授权器的一个账号。{@code problem} 非空时不能绑；{@code baseUrl} 是要使用者确认的站点地址。 */
    public record Check(String problem, SessionKey authenticator, String baseUrl, List<String> warnings) {
        public Check {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean allowed() {
            return problem == null;
        }
    }

    static final String PREFIX = "auth:";
    static final String REASON_ACCOUNT_DELETED = "account_deleted";
    static final String REASON_AUTH_ACCOUNT_DELETED = "authenticator_account_deleted";
    static final String REASON_ADDRESS_CHANGED = "address_changed";
    static final String REASON_SOURCE_CHANGED = "authenticator_source_changed";
    static final String REASON_NO_LONGER_USES = "no_longer_uses";
    static final String REASON_WIDENED = "address_widened";
    static final String REASON_PACKAGE_REMOVED = "package_removed";

    private final AuthBindingStore store;
    private final SessionManager sessions;
    private final ConchRegistry registry;
    private final ConchConfigStore conchConfig;
    private final AccountStore accounts;
    private final CredentialVault vault;
    private final Identities identities;
    private final Predicate<String> disabled;
    private final Object judging = new Object();

    Authenticators(
            AuthBindingStore store,
            SessionManager sessions,
            ConchRegistry registry,
            ConchConfigStore conchConfig,
            AccountStore accounts,
            CredentialVault vault,
            Identities identities,
            Predicate<String> disabled
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.conchConfig = Objects.requireNonNull(conchConfig, "conchConfig");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.disabled = disabled == null ? name -> false : disabled;
    }

    /** 授权器账号共用罐的 id。授权器账号不管有没有被引用都用这一个罐。 */
    static String jarId(SessionKey authenticator) {
        return PREFIX + authenticator.origin().name() + "/" + authenticator.conch() + "/" + authenticator.account();
    }

    /**
     * 会话管理器看到的「共用」：使用者建的身份照旧；授权器账号和绑到它的账号共用授权器账号的那一个罐。
     * 授权器罐里的 Cookie 存在授权器账号自己的 {@code sessions} 行里。
     */
    static SharedSessions shared(
            SharedSessions identities,
            AuthBindingStore bindings,
            ConchRegistry registry,
            SessionPersistence persistence
    ) {
        return new SharedSessions() {
            @Override
            public String identityOf(SessionKey key) {
                AuthBinding binding = bindings.find(key);
                if (binding != null) {
                    return jarId(binding.authenticator());
                }
                ConchDoc doc = registry.find(key.conch(), key.origin()).orElse(null);
                if (AuthenticatorChecks.isAuthenticator(doc)) {
                    return jarId(key);
                }
                return identities.identityOf(key);
            }

            @Override
            public List<SessionKey> members(String identity) {
                SessionKey authenticator = parse(identity);
                if (authenticator == null) {
                    return identities.members(identity);
                }
                ArrayList<SessionKey> members = new ArrayList<>();
                members.add(authenticator);
                for (AuthBinding binding : bindings.consumersOf(authenticator)) {
                    members.add(binding.account());
                }
                return members;
            }

            @Override
            public List<StoredCookie> load(String identity) {
                SessionKey authenticator = parse(identity);
                return authenticator == null ? identities.load(identity) : persistence.load(authenticator);
            }

            @Override
            public void save(String identity, List<StoredCookie> cookies) {
                SessionKey authenticator = parse(identity);
                if (authenticator == null) {
                    identities.save(identity, cookies);
                } else {
                    persistence.save(authenticator, cookies);
                }
            }

            @Override
            public void delete(String identity) {
                SessionKey authenticator = parse(identity);
                if (authenticator == null) {
                    identities.delete(identity);
                } else {
                    persistence.delete(authenticator);
                }
            }
        };
    }

    /** 包名和账号名都不含斜杠，身份的 id 不以 auth: 开头。 */
    private static SessionKey parse(String identity) {
        if (identity == null || !identity.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = identity.substring(PREFIX.length()).split("/", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new SessionKey(Origin.valueOf(parts[0]), parts[1], parts[2]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public List<AuthBinding> list() {
        return store.list();
    }

    public AuthBinding find(SessionKey account) {
        return store.find(account);
    }

    public List<AuthBinding> consumersOf(SessionKey authenticator) {
        return store.consumersOf(authenticator);
    }

    /** 这个本地包有没有账号绑了授权器，或者它是授权器并且有账号被引用。不查库。 */
    public boolean hasBoundLocal(String conch) {
        return conch != null && (!store.bindingsOf(Origin.LOCAL, conch).isEmpty()
                || !store.consumersOf(Origin.LOCAL, conch).isEmpty());
    }

    public Check check(Origin origin, String conch, String account, String authAccount) {
        ConchDoc doc = registry.find(conch, origin).orElse(null);
        if (doc == null) {
            return refuse("规则包不在本机");
        }
        if (!AuthenticatorChecks.uses(doc)) {
            return refuse("这个包没有写 auth.use，不需要绑定授权器");
        }
        if (!hasAccount(origin, conch, account)) {
            return refuse("账号不存在");
        }
        SessionKey key = new SessionKey(origin, conch, account);
        if (identities.identityOf(key) != null) {
            return refuse("这个账号已经绑到一个身份，先解除再绑定授权器");
        }
        ConchRegistry.Active active = registry.authenticator(doc.auth().use()).orElse(null);
        if (active == null) {
            return refuse("本机没有授权器 " + doc.auth().use() + "，先安装它");
        }
        if (disabled.test(active.doc().conch())) {
            return refuse("授权器 " + active.doc().conch() + " 已停用");
        }
        List<ValidationIssue> problems = AuthenticatorChecks.link(doc, active.doc());
        if (!problems.isEmpty()) {
            return refuse(problems.get(0).message());
        }
        if (authAccount == null || !hasAccount(active.origin(), active.doc().conch(), authAccount)) {
            return refuse("授权器账号不存在");
        }
        String site = site(origin, doc);
        if (site == null) {
            return refuse("这个包的站点地址还没定下来，先填好 base_url");
        }
        if (!allowed(active.doc(), active.origin(), site)) {
            return refuse("授权器 " + active.doc().conch() + " 不允许用于 " + site + "（auth.allow_hosts）");
        }
        ArrayList<String> warnings = new ArrayList<>();
        if (origin == Origin.LOCAL && active.origin() == Origin.OFFICIAL) {
            warnings.add("这是未经过 Reef 签名的本地规则包。绑定后它能用授权器登录出来的会话和导出的值，请确认本地规则可信。");
        }
        if (active.origin() == Origin.LOCAL) {
            warnings.add("授权器是未经过 Reef 签名的本地规则包。");
        }
        warnings.add("绑定后 " + conch + " 会用授权器账号 " + authAccount + " 的登录访问 " + site + "。");
        return new Check(null, new SessionKey(active.origin(), active.doc().conch(), authAccount), site, warnings);
    }

    /** 绑定或改绑。条件不满足时抛 {@link IllegalArgumentException}，消息就是原因。 */
    public Check bind(
            Origin origin, String conch, String account, String authAccount, String confirmedBaseUrl, String actor) {
        SessionKey key = new SessionKey(origin, conch, account);
        Check check;
        synchronized (judging) {
            check = check(origin, conch, account, authAccount);
            if (!check.allowed()) {
                throw new IllegalArgumentException(check.problem());
            }
            if (!check.baseUrl().equals(confirmedBaseUrl)) {
                throw new IllegalArgumentException("规则包的站点地址已变化，请重新检查后再绑定");
            }
            AuthBinding current = store.find(key);
            if (current != null && current.authenticator().equals(check.authenticator())
                    && current.boundBaseUrl().equals(check.baseUrl())) {
                return check;
            }
            store.bind(key, check.authenticator(), check.baseUrl(), actor);
        }
        sessions.rebound(key);
        return check;
    }

    public boolean unbind(Origin origin, String conch, String account, String actor) {
        return leave(new SessionKey(origin, conch, account), null, actor);
    }

    /** 这个账号有没有绑授权器；身份那边用它保证两种共用不同时存在。 */
    boolean bound(SessionKey key) {
        return store.find(key) != null;
    }

    /** 账号被删：它自己的绑定作废；它是授权器账号时，绑到它的账号都作废。 */
    void accountDeleted(SessionKey key, String actor) {
        leave(key, REASON_ACCOUNT_DELETED, actor);
        for (AuthBinding binding : store.consumersOf(key)) {
            leave(binding.account(), REASON_AUTH_ACCOUNT_DELETED, actor);
        }
    }

    /** 使用者退订或删掉了一个包（某个来源那一份）：它的账号的绑定作废；它是授权器时，绑到它的都作废。 */
    public void packageRemoved(Origin origin, String conch, String actor) {
        if (registry.find(conch, origin).isPresent()) {
            return;
        }
        for (AuthBinding binding : store.bindingsOf(origin, conch)) {
            leave(binding.account(), REASON_PACKAGE_REMOVED, actor);
        }
        for (AuthBinding binding : store.consumersOf(origin, conch)) {
            leave(binding.account(), REASON_PACKAGE_REMOVED, actor);
        }
    }

    /** 本地包的地址范围扩大了：它的绑定作废；它是授权器时，绑到它的都作废。 */
    void widened(String conch) {
        for (AuthBinding binding : store.bindingsOf(Origin.LOCAL, conch)) {
            leave(binding.account(), REASON_WIDENED, Actors.SYNC);
        }
        for (AuthBinding binding : store.consumersOf(Origin.LOCAL, conch)) {
            leave(binding.account(), REASON_WIDENED, Actors.SYNC);
        }
    }

    /**
     * 包更新、切换来源或改了地址配置之后：不再满足绑定条件的绑定作废，使用者要重新确认。
     * 包这会儿不在本机（启动时还没载入、被撤下）的绑定不动。返回作废的账号。
     */
    public List<SessionKey> revalidate(String actor) {
        ArrayList<SessionKey> left = new ArrayList<>();
        for (AuthBinding binding : store.list()) {
            String reason = stale(binding);
            if (reason != null && leave(binding.account(), reason, actor)) {
                left.add(binding.account());
            }
        }
        return left;
    }

    private String stale(AuthBinding binding) {
        SessionKey key = binding.account();
        ConchDoc doc = registry.find(key.conch(), key.origin()).orElse(null);
        if (doc == null) {
            return null;
        }
        if (!AuthenticatorChecks.uses(doc) || !doc.auth().use().equals(binding.authenticator().conch())) {
            return REASON_NO_LONGER_USES;
        }
        if (!binding.boundBaseUrl().equals(site(key.origin(), doc))) {
            return REASON_ADDRESS_CHANGED;
        }
        ConchRegistry.Active active = registry.authenticator(binding.authenticator().conch()).orElse(null);
        if (active == null) {
            // 授权器这会儿不在本机，或者同名的包不再是授权器。后一种不会再回来。
            return registry.installed(binding.authenticator().conch()).isPresent() ? REASON_NO_LONGER_USES : null;
        }
        if (active.origin() != binding.authenticator().origin()) {
            return REASON_SOURCE_CHANGED;
        }
        if (!allowed(active.doc(), active.origin(), binding.boundBaseUrl())) {
            return REASON_ADDRESS_CHANGED;
        }
        return null;
    }

    private boolean leave(SessionKey key, String reason, String actor) {
        AuthBinding removed;
        synchronized (judging) {
            removed = store.unbind(key, reason, actor);
        }
        if (removed == null) {
            return false;
        }
        sessions.rebound(key);
        return true;
    }

    @Override
    public Link resolve(SessionKey key, ConchDoc conch) {
        AuthBinding binding = store.find(key);
        if (binding == null || conch == null || !AuthenticatorChecks.uses(conch)) {
            return null;
        }
        SessionKey target = binding.authenticator();
        ConchRegistry.Active active = registry.authenticator(conch.auth().use()).orElse(null);
        if (active == null || active.origin() != target.origin() || !active.doc().conch().equals(target.conch())
                || disabled.test(target.conch())) {
            return null;
        }
        ConchDoc authenticator = active.doc();
        if (!AuthenticatorChecks.link(conch, authenticator).isEmpty()) {
            return null;
        }
        String site = site(key.origin(), conch);
        if (site == null || !site.equals(binding.boundBaseUrl()) || !allowed(authenticator, target.origin(), site)) {
            return null;
        }
        if (!hasAccount(target.origin(), target.conch(), target.account())) {
            return null;
        }
        Map<String, String> stored = conchConfig.all(target.origin(), target.conch());
        if (!DeclaredSettings.missingConfig(authenticator, stored).isEmpty()
                || !DeclaredSettings.missingCredentials(
                        authenticator, vault.fieldNames(target.origin(), target.conch(), target.account())).isEmpty()) {
            return null;
        }
        Map<String, String> secrets;
        try {
            secrets = vault.decryptForExecution(target.origin(), target.conch(), target.account());
        } catch (RuntimeException ex) {
            return null;
        }
        return new Link(target, authenticator, secrets, DeclaredSettings.mergedConfig(authenticator, stored));
    }

    private String site(Origin origin, ConchDoc doc) {
        Map<String, String> merged = DeclaredSettings.mergedConfig(doc, conchConfig.all(origin, doc.conch()));
        return SiteAddress.effective(doc, merged.get("base_url"));
    }

    /** 授权器没写 {@code auth.allow_hosts} 时不限制；写了就只有列出的地址能用它。 */
    private boolean allowed(ConchDoc authenticator, Origin origin, String site) {
        List<String> entries = authenticator.auth().allowHosts();
        if (entries.isEmpty()) {
            return true;
        }
        Map<String, String> merged = DeclaredSettings.mergedConfig(
                authenticator, conchConfig.all(origin, authenticator.conch()));
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            String name = AuthOrigins.configName(entry);
            if (name != null) {
                if (site.equals(SiteAddress.of(merged.get(name)))) {
                    return true;
                }
            } else if (entry.contains("*")) {
                if (wildcard(entry, site)) {
                    return true;
                }
            } else if (site.equals(SiteAddress.of(entry))) {
                return true;
            }
        }
        return false;
    }

    /** {@code scheme://*.域名[:port]}：协议和端口要相同，主机是这个域名的子域。 */
    private static boolean wildcard(String entry, String site) {
        try {
            URI pattern = URI.create(entry.replace("*.", "x."));
            URI actual = URI.create(site);
            if (pattern.getHost() == null || actual.getHost() == null
                    || !pattern.getScheme().equalsIgnoreCase(actual.getScheme())
                    || port(pattern) != port(actual)) {
                return false;
            }
            String suffix = pattern.getHost().substring(1).toLowerCase(Locale.ROOT);
            String host = actual.getHost().toLowerCase(Locale.ROOT);
            return host.length() > suffix.length() && host.endsWith(suffix);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private Check refuse(String problem) {
        return new Check(problem, null, null, List.of());
    }

    private boolean hasAccount(Origin origin, String conch, String account) {
        for (Account existing : accounts.list(origin, conch)) {
            if (existing.account().equals(account)) {
                return true;
            }
        }
        return false;
    }
}
