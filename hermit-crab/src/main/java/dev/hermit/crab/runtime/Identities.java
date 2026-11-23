package dev.hermit.crab.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.Step;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Identity;
import dev.hermit.crab.store.IdentityStore;
import dev.hermit.crab.store.Origin;

/**
 * 身份：使用者把不同规则包的账号绑到一起，共用一份 Cookie 和一把锁（会话那一半在 {@link SessionManager}）。
 * 只有控制台能改；规则包、API Key 和 MCP 工具都碰不到这里。任何绑定都要使用者点名，不按账号名或凭证自动绑。
 */
public final class Identities {
    /** 绑定前比较两边文本类凭证的结果。不带值。 */
    public enum Credentials {
        /** 有一个文本凭证的值相同，看起来是同一个登录名。 */
        SAME,
        /** 两边都填了文本凭证，没有一个相同。 */
        DIFFERENT,
        /** 有一边没有文本凭证，或没填、读不出来。 */
        UNKNOWN
    }

    public record Peer(SessionKey member, List<String> addresses, Credentials credentials) {
        public Peer {
            addresses = List.copyOf(addresses);
        }
    }

    /**
     * 一个账号能不能绑到一个身份。{@code problem} 非空时不能绑；{@code warnings} 只是提醒。
     * {@code peers} 是身份现有的成员，各带和这个包相同的地址、凭证比较结果。
     */
    public record Check(String problem, List<Peer> peers, List<String> warnings) {
        public Check {
            peers = List.copyOf(peers);
            warnings = List.copyOf(warnings);
        }

        public boolean allowed() {
            return problem == null;
        }
    }

    static final String REASON_ACCOUNT_DELETED = "account_deleted";
    static final String REASON_WIDENED = "address_widened";
    static final String REASON_PACKAGE_REMOVED = "package_removed";
    static final String REASON_ADDRESS_CHANGED = "address_changed";

    private static final Pattern LOGIN_REF = Pattern.compile("\\{\\{[^}]*\\blogin\\.");

    private final IdentityStore store;
    /**
     * 各成员的包在身份上一次「成员都在、连成一组」时的地址，只在内存里。启动载入时每放进一个包都会判断一次，
     * 全部载入后就记齐了。读地址和写这张表都在 {@link #judging} 里：两个线程同时判断时，后写的一定是更新的地址。
     */
    private final ConcurrentMap<SessionKey, Set<String>> seen = new ConcurrentHashMap<>();
    /** 只包住读地址、读写绑定和 {@link #seen}；等账号锁的 {@code sessions.rebound} 在外面做。 */
    private final Object judging = new Object();
    private final SessionManager sessions;
    private final ConchRegistry registry;
    private final ConchConfigStore conchConfig;
    private final AccountStore accounts;
    private final CredentialVault vault;

    Identities(
            IdentityStore store,
            SessionManager sessions,
            ConchRegistry registry,
            ConchConfigStore conchConfig,
            AccountStore accounts,
            CredentialVault vault
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.conchConfig = Objects.requireNonNull(conchConfig, "conchConfig");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    public List<Identity> list() {
        return store.list();
    }

    /** 这个账号绑到的身份 id；没绑为 null。 */
    public String identityOf(SessionKey key) {
        return store.identityOf(key);
    }

    /** 这个本地包有没有任意账号绑到了身份。不查库。 */
    public boolean hasBoundLocal(String conch) {
        return conch != null && !store.boundAccounts(Origin.LOCAL, conch).isEmpty();
    }

    public SessionManager.Cleared lastCleared(String identity) {
        return sessions.lastCleared(identity);
    }

    public Identity create(String name, String actor) {
        return store.create(name, actor);
    }

    public void rename(String id, String name, String actor) {
        store.rename(id, name, actor);
    }

    /** 删掉身份：成员都回到各自独立的会话，要重新登录。 */
    public void delete(String id, String actor) {
        List<SessionKey> members;
        synchronized (judging) {
            members = store.delete(id, actor);
            for (SessionKey member : members) {
                seen.remove(member);
            }
        }
        for (SessionKey member : members) {
            sessions.rebound(member);
        }
        sessions.dropIdentity(id);
    }

    /**
     * 这个包（某个来源那一份）会访问的地址：站点加认证地址，按这台 Crab 的配置。
     * 包不在本机、是 {@code driver: mcp} 或地址定不下来时为空。和停用与否无关。
     */
    public Set<String> addresses(Origin origin, String conch) {
        ConchDoc doc = registry.find(conch, origin).orElse(null);
        if (doc == null || doc.upstream() != null) {
            return Set.of();
        }
        Map<String, String> merged = DeclaredSettings.mergedConfig(doc, conchConfig.all(origin, conch));
        TreeSet<String> all = new TreeSet<>();
        String site = SiteAddress.effective(doc, merged.get("base_url"));
        if (site != null) {
            all.add(site);
        }
        all.addAll(AuthOrigins.effective(doc, merged));
        return all;
    }

    public Check check(String id, Origin origin, String conch, String account) {
        Identity identity = store.find(id);
        if (identity == null) {
            throw new IllegalArgumentException("身份不存在");
        }
        SessionKey key = new SessionKey(origin, conch, account);
        ConchDoc doc = registry.find(conch, origin).orElse(null);
        if (doc == null) {
            return new Check("规则包不在本机", List.of(), List.of());
        }
        if (doc.upstream() != null) {
            return new Check("driver: mcp 的包没有 Cookie 会话，不能绑定身份", List.of(), List.of());
        }
        if (!hasAccount(origin, conch, account)) {
            return new Check("账号不存在", List.of(), List.of());
        }
        String current = store.identityOf(key);
        if (current != null && !current.equals(id)) {
            return new Check("这个账号已经绑到另一个身份，先解除再绑定", List.of(), List.of());
        }
        Set<String> mine = addresses(origin, conch);
        if (mine.isEmpty()) {
            return new Check("这个包的站点地址还没定下来，先填好 base_url", List.of(), List.of());
        }
        ArrayList<Peer> peers = new ArrayList<>();
        boolean linked = false;
        boolean others = false;
        boolean peerUsesLogin = false;
        boolean mixesLocalAndOfficial = false;
        for (SessionKey member : identity.members()) {
            if (member.equals(key)) {
                continue;
            }
            if (member.origin() == origin && member.conch().equals(conch)) {
                return new Check(
                        "这个身份下已经有 " + conch + " 的账号 " + member.account() + "，一个包只能绑一个账号",
                        List.of(), List.of());
            }
            others = true;
            if ((origin == Origin.LOCAL && member.origin() == Origin.OFFICIAL)
                    || (origin == Origin.OFFICIAL && member.origin() == Origin.LOCAL)) {
                mixesLocalAndOfficial = true;
            }
            ArrayList<String> common = new ArrayList<>(addresses(member.origin(), member.conch()));
            common.retainAll(mine);
            linked |= !common.isEmpty();
            peers.add(new Peer(member, common, compare(key, doc, member)));
            ConchDoc peer = registry.find(member.conch(), member.origin()).orElse(null);
            peerUsesLogin |= peer != null && usesLoginValues(peer);
        }
        if (others && !linked) {
            return new Check(
                    "这个包和身份里现有的成员没有相同的地址（站点或认证地址），共用 Cookie 没有意义", peers, List.of());
        }
        ArrayList<String> warnings = new ArrayList<>();
        if (mixesLocalAndOfficial) {
            warnings.add("其中包含未经过 Reef 签名的本地规则包，绑定后会与正式规则包共用这份登录 Cookie，请确认本地规则可信。");
        }
        for (Peer peer : peers) {
            if (peer.credentials() == Credentials.DIFFERENT) {
                warnings.add("和 " + peer.member().conch() + " 的账号 " + peer.member().account()
                        + " 填的登录名不一样。两个不同的人绑到一起，会用错的人的登录去操作。");
            }
        }
        if (doc.auth() == null || doc.auth().aliveCheck() == null) {
            warnings.add("这个包没有写 alive_check：绑定后它第一次被调用仍会跑一次自己的登录步骤。");
        }
        if (peerUsesLogin && usesLoginValues(doc)) {
            warnings.add("这个包和身份里的另一个包都要用登录返回的值。网站在新登录后让旧值失效时，绑定后仍会交替重新登录。");
        }
        return new Check(null, peers, warnings);
    }

    /** 绑定。条件不满足时抛 {@link IllegalArgumentException}，消息就是原因。 */
    public Check bind(String id, Origin origin, String conch, String account, String actor) {
        SessionKey key = new SessionKey(origin, conch, account);
        Check check;
        synchronized (judging) {
            check = check(id, origin, conch, account);
            if (!check.allowed()) {
                throw new IllegalArgumentException(check.problem());
            }
            if (id.equals(store.identityOf(key))) {
                seen.put(key, addresses(origin, conch));
                return check;
            }
            store.bind(id, key, actor);
            seen.put(key, addresses(origin, conch));
        }
        sessions.rebound(key);
        return check;
    }

    /** 解除绑定：这个账号回到自己独立的会话，身份的 Cookie 留给其它成员；最后一个成员离开时清空。 */
    public boolean unbind(Origin origin, String conch, String account, String actor) {
        return leave(new SessionKey(origin, conch, account), null, actor);
    }

    void accountDeleted(SessionKey key, String actor) {
        leave(key, REASON_ACCOUNT_DELETED, actor);
    }

    /** 使用者退订或删掉了这个包：它的账号离开身份，其它成员接着用身份里的 Cookie。 */
    public void packageRemoved(Origin origin, String conch, String actor) {
        if (registry.find(conch, origin).isPresent()) {
            return;
        }
        for (SessionKey key : store.boundAccounts(origin, conch)) {
            leave(key, REASON_PACKAGE_REMOVED, actor);
        }
    }

    /** 本地包的地址范围扩大了：它的账号都离开身份，新地址拿不到别的包登录出来的 Cookie。 */
    void widened(String conch) {
        for (SessionKey key : store.boundAccounts(Origin.LOCAL, conch)) {
            leave(key, REASON_WIDENED, Actors.SYNC);
        }
    }

    /**
     * 包更新、切换来源或改了地址配置之后：成员和身份里其它在本机的成员不再有相同地址时，让它离开。
     * 包不在本机的成员不动（退订保留账号和凭证，也保留绑定）。返回离开的成员。
     */
    public List<SessionKey> revalidate(String actor) {
        ArrayList<SessionKey> left = new ArrayList<>();
        LinkedHashMap<SessionKey, String> detached = new LinkedHashMap<>();
        synchronized (judging) {
            if (!store.anyBound()) {
                return left;
            }
            for (Identity identity : store.list()) {
                LinkedHashMap<SessionKey, Set<String>> present = new LinkedHashMap<>();
                ArrayList<SessionKey> invalidMembers = new ArrayList<>();
                boolean hasMissingPackage = false;
                for (SessionKey member : identity.members()) {
                    ConchDoc doc = registry.find(member.conch(), member.origin()).orElse(null);
                    if (doc == null) {
                        hasMissingPackage = true;
                        continue;
                    }
                    Set<String> mine = addresses(member.origin(), member.conch());
                    if (mine.isEmpty()) {
                        invalidMembers.add(member);
                    } else {
                        present.put(member, mine);
                    }
                }
                // 已载入但不再有站点地址的成员（例如变成 driver: mcp 或清空了 base_url）直接解绑，
                // 即使同身份还有别的成员的包暂时不在本机，也不让它们继续留在身份里。
                for (SessionKey member : invalidMembers) {
                    String id = detach(member, REASON_ADDRESS_CHANGED, actor);
                    if (id != null) {
                        detached.put(member, id);
                        left.add(member);
                    }
                }
                // 有成员的包这会儿不在本机（启动时还没载入、文件读不出来、被 Reef 撤下）：它可能正是
                // 把另外两个成员连起来的那一个，其余成员的连通性先不动，等它回来再判断。记下的地址也不动。
                if (hasMissingPackage || present.isEmpty()) {
                    continue;
                }
                List<List<SessionKey>> groups = groups(present);
                if (groups.size() < 2) {
                    // 只有一个成员时也记：它自己换了地址之后再有人加入，不该还拿旧地址当「上次」。
                    seen.putAll(present);
                    continue;
                }
                // 一个身份只有一份 Cookie：留下最大的一组，其余的都离开。一样大时留下地址没变的那一组：
                // 谁动了看地址本身，不看是哪一次通知带来的，通知的先后和同名的两份都不会判错。
                List<SessionKey> kept = groups.get(0);
                for (List<SessionKey> group : groups) {
                    if (group.size() > kept.size()
                            || (group.size() == kept.size() && moved(group, present) < moved(kept, present))) {
                        kept = group;
                    }
                }
                for (SessionKey member : kept) {
                    seen.put(member, present.get(member));
                }
                for (List<SessionKey> group : groups) {
                    if (group == kept) {
                        continue;
                    }
                    for (SessionKey member : group) {
                        String id = detach(member, REASON_ADDRESS_CHANGED, actor);
                        if (id != null) {
                            detached.put(member, id);
                            left.add(member);
                        }
                    }
                }
            }
        }
        detached.forEach(this::settle);
        return left;
    }

    /** 这一组里地址和上次判断时不一样的成员数。没记过的（刚启动）不算动过。 */
    private int moved(List<SessionKey> group, Map<SessionKey, Set<String>> present) {
        int count = 0;
        for (SessionKey member : group) {
            Set<String> before = seen.get(member);
            if (before != null && !before.equals(present.get(member))) {
                count++;
            }
        }
        return count;
    }

    /** 按「有一个地址相同」连起来的各组成员。 */
    private static List<List<SessionKey>> groups(Map<SessionKey, Set<String>> present) {
        ArrayList<List<SessionKey>> groups = new ArrayList<>();
        ArrayList<SessionKey> rest = new ArrayList<>(present.keySet());
        while (!rest.isEmpty()) {
            ArrayList<SessionKey> group = new ArrayList<>();
            group.add(rest.remove(0));
            for (int index = 0; index < group.size(); index++) {
                Set<String> mine = present.get(group.get(index));
                for (int other = 0; other < rest.size(); ) {
                    if (Collections.disjoint(mine, present.get(rest.get(other)))) {
                        other++;
                    } else {
                        group.add(rest.remove(other));
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private boolean leave(SessionKey key, String reason, String actor) {
        String id = detach(key, reason, actor);
        if (id == null) {
            return false;
        }
        settle(key, id);
        return true;
    }

    /** 解除绑定这一半：只动库和记下的地址。返回原来的身份，本来就没绑时是 null。 */
    private String detach(SessionKey key, String reason, String actor) {
        synchronized (judging) {
            String id = store.unbind(key, reason, actor);
            seen.remove(key);
            return id;
        }
    }

    /** 解除绑定之后会话这一半：要等这个账号正在跑的调用，不在 {@link #judging} 里做。 */
    private void settle(SessionKey key, String id) {
        sessions.rebound(key);
        sessions.clearIdentityIfEmpty(id);
    }

    private boolean hasAccount(Origin origin, String conch, String account) {
        for (Account existing : accounts.list(origin, conch)) {
            if (existing.account().equals(account)) {
                return true;
            }
        }
        return false;
    }

    private Credentials compare(SessionKey key, ConchDoc doc, SessionKey member) {
        ConchDoc peer = registry.find(member.conch(), member.origin()).orElse(null);
        if (peer == null) {
            return Credentials.UNKNOWN;
        }
        Set<String> mine = textValues(key, doc);
        Set<String> theirs = textValues(member, peer);
        if (mine.isEmpty() || theirs.isEmpty()) {
            return Credentials.UNKNOWN;
        }
        return Collections.disjoint(mine, theirs) ? Credentials.DIFFERENT : Credentials.SAME;
    }

    /** 这个账号填了的 {@code type: text} 凭证值。{@code secret} 不读出来比较。 */
    private Set<String> textValues(SessionKey key, ConchDoc doc) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, CredentialField> entry : doc.credentials().entrySet()) {
            if ("text".equals(entry.getValue().type())) {
                names.add(entry.getKey());
            }
        }
        if (names.isEmpty()) {
            return Set.of();
        }
        Map<String, String> stored;
        try {
            stored = vault.decryptForExecution(key.origin(), key.conch(), key.account());
        } catch (RuntimeException ex) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String name : names) {
            String value = stored.get(name);
            if (value != null && !value.isBlank()) {
                values.add(value.strip());
            }
        }
        return values;
    }

    /** 能力步骤、探活请求或 auth.cookies 里用了登录步骤留下的值。 */
    private static boolean usesLoginValues(ConchDoc doc) {
        if (doc.auth() != null) {
            if (!doc.auth().cookies().isEmpty()) {
                return true;
            }
            if (doc.auth().aliveCheck() != null
                    && LOGIN_REF.matcher(String.valueOf(doc.auth().aliveCheck().request())).find()) {
                return true;
            }
        }
        for (Action action : doc.actions().values()) {
            for (Step step : action.steps()) {
                if (LOGIN_REF.matcher(String.valueOf(step.def())).find()) {
                    return true;
                }
            }
        }
        return false;
    }
}
