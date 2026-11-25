package dev.hermit.reef.conch;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.AuthenticatorChecks;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ValidationIssue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 业务包（{@code auth.use}）和授权器（{@code driver: auth}）之间的依赖。
 * 规则只看双方当前发布的版本：业务包发布时授权器必须已发布、没停用、对得上；
 * 授权器换版本或停用时，不能让正在发布的业务包对不上。
 *
 * <p>两边读对方都是加锁读：业务包一侧先锁自己的版本行、再锁授权器的版本行；授权器一侧先锁自己的版本行、
 * 再锁引用它的当前版本行。并发的发布总有一边能看到另一边已提交的结果。业务包和它的授权器同时发布时两边的
 * 加锁顺序相反，数据库会判成死锁让其中一个失败，重试即可。
 */
@Component
class AuthDependencies {

    private final ConchRepository repository;
    private final ConchAvailabilityRepository availability;
    private final ConchStorage storage;

    AuthDependencies(ConchRepository repository, ConchAvailabilityRepository availability, ConchStorage storage) {
        this.repository = repository;
        this.availability = availability;
        this.storage = storage;
    }

    /** 业务包引用的授权器名，没有引用时是 null。写进版本行的 {@code auth_use}。 */
    static String authUse(ConchDoc doc) {
        return AuthenticatorChecks.uses(doc) ? doc.auth().use() : null;
    }

    /**
     * 业务包这一版现在能不能发布。调用方必须在事务里，并且已经锁住业务包自己的版本行。
     * 返回的每一项都是阻止发布的错误；不引用授权器时是空的。
     */
    List<ValidationIssue> consumerProblems(ConchDoc consumer) {
        String name = authUse(consumer);
        if (name == null) {
            return List.of();
        }
        VersionRow current = current(repository.lockConch(name));
        if (current == null) {
            return List.of(issue("auth.use", "授权器 " + name + " 还没有在 Reef 上发布，先发布授权器"));
        }
        if (availability.isDisabled(name)) {
            return List.of(issue("auth.use", "授权器 " + name + " 已停用"));
        }
        return AuthenticatorChecks.link(consumer, read(current));
    }

    /**
     * 把 {@code next} 发布成 {@code conch} 的当前版本后，哪些正在发布的业务包会对不上。
     * 调用方必须在事务里，并且已经锁住 {@code conch} 的版本行。每一项是一句给管理员看的话。
     */
    List<String> brokenConsumers(String conch, ConchDoc next) {
        ArrayList<String> broken = new ArrayList<>();
        for (VersionRow row : repository.listCurrentUsing(conch)) {
            if (availability.isDisabled(row.conch())) {
                continue;
            }
            List<ValidationIssue> problems = AuthenticatorChecks.link(read(row), next);
            if (!problems.isEmpty()) {
                broken.add(row.conch() + " " + row.version() + "：" + problems.get(0).message());
            }
        }
        return broken;
    }

    /**
     * 上传、提交审批和审批时的检查：这一版发布后依赖对不对得上。和发布时是同一套规则，提前给出来。
     * 调用方必须在事务里，并且已经锁住 {@code doc} 这个包的版本行。
     */
    List<ValidationIssue> uploadProblems(ConchDoc doc) {
        ArrayList<ValidationIssue> problems = new ArrayList<>(consumerProblems(doc));
        for (String broken : brokenConsumers(doc.conch(), doc)) {
            problems.add(issue(
                    AuthenticatorChecks.isAuthenticator(doc) ? "auth.exports" : "driver",
                    "正在发布的规则包会对不上这一版：" + broken + "。先更新或停用它"));
        }
        return problems;
    }

    /** 正在发布、没停用、引用 {@code conch} 的业务包。调用方必须已经锁住 {@code conch} 的版本行。 */
    List<String> activeConsumers(String conch) {
        return repository.listCurrentUsing(conch).stream()
                .map(VersionRow::conch)
                .filter(name -> !availability.isDisabled(name))
                .toList();
    }

    /** 重新启用业务包时用：它当前发布的版本现在还对得上授权器吗。调用方必须已经锁住业务包的版本行。 */
    List<ValidationIssue> currentConsumerProblems(List<VersionRow> rows) {
        VersionRow current = current(rows);
        if (current == null || current.authUse() == null) {
            return List.of();
        }
        return consumerProblems(read(current));
    }

    private static VersionRow current(List<VersionRow> rows) {
        return rows.stream().filter(VersionRow::current).findFirst().orElse(null);
    }

    private ConchDoc read(VersionRow row) {
        return ConchParser.parse(new String(storage.readYaml(row.conch(), row.version()), StandardCharsets.UTF_8));
    }

    private static ValidationIssue issue(String path, String message) {
        return new ValidationIssue(ValidationCodes.AUTHENTICATOR, path, message);
    }
}
