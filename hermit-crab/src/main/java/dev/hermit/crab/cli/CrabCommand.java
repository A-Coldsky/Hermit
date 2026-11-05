package dev.hermit.crab.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.config.ConfigFileException;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.config.CrabConfigLoader;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.mcp.McpStdio;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.ReefBootstrap;
import dev.hermit.crab.runtime.ReefBootstrapException;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.secure.MasterKeyException;
import dev.hermit.crab.sync.ConchCatalog;
import dev.hermit.crab.sync.ConchOutcome;
import dev.hermit.crab.sync.LocalConchException;
import dev.hermit.crab.sync.LocalConches;
import dev.hermit.crab.sync.SubscriptionException;
import dev.hermit.crab.sync.UpdateException;
import dev.hermit.crab.sync.SyncError;
import dev.hermit.crab.sync.SyncException;
import dev.hermit.crab.sync.SyncReport;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.ApiKeySummary;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.DatabaseInUseException;

/**
 * Crab command line. Credential values are read from the console, one stdin line, or
 * {@code --from-env}. They are never accepted as an argument.
 */
public final class CrabCommand {
    private static final String USAGE = """
            用法：
              crab --help
              crab serve
              crab sync
              crab reef import <file>
              crab catalog
              crab subscribe <conch>
              crab unsubscribe <conch>
              crab update <conch>
              crab account list <conch>
              crab account add|remove|default <conch> <name>
              crab credential set <conch> <account> <field> [--from-env VAR]
              crab credential list <conch> <account>
              crab config get <conch> [key]
              crab config set <conch> <key> <value>
              crab config unset <conch> <key>
              crab audit list [--conch NAME] [--account NAME] [--limit N] [--page N]
              crab apikey create <name> [--all] [--conches a,b] [--actions conch.action] [--read-only]
              crab apikey list
              crab apikey revoke <id>
              crab local import <file> [--replace]
              crab local list
              crab local reload
              crab local delete <conch>
              crab local export <conch> [--out file]
              crab mcp-stdio [--help] [--url http://127.0.0.1:7777/mcp] [--account NAME]
            """;

    public int run(String[] args, Function<String, String> env, InputStream in, PrintStream out, PrintStream err) {
        try {
            return dispatch(args == null ? new String[0] : args, env, in, out, err);
        } catch (DatabaseInUseException ex) {
            err.println(DatabaseInUseException.MESSAGE);
            return 2;
        } catch (ConchRejectedException ex) {
            err.println("规则包未通过校验。");
            for (ValidationIssue issue : ex.issues()) {
                err.println(issue.code() + " " + issue.path());
            }
            return 1;
        } catch (ConchParseException ex) {
            err.println("已安装的规则包无法解析。");
            return 1;
        } catch (ConfigFileException ex) {
            err.println(ex.getMessage());
            return 1;
        } catch (AccountNotFoundException ex) {
            err.println(ex.account() == null ? "没有默认账号。" : "没有这个账号。");
            return 1;
        } catch (MasterKeyException ex) {
            err.println(clientMessage(ex));
            return 1;
        } catch (ReefBootstrapException ex) {
            err.println(ex.getMessage());
            return 1;
        } catch (LocalConchException ex) {
            err.println(ex.getMessage());
            for (ValidationIssue issue : ex.errors()) {
                err.println(issue.code() + " " + issue.path());
            }
            return 1;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            err.println(clientMessage(ex));
            return 1;
        } catch (Exception ex) {
            err.println(ex.getClass().getName());
            return 1;
        }
    }

    private int dispatch(String[] args, Function<String, String> env, InputStream in, PrintStream out, PrintStream err)
            throws Exception {
        if (args.length == 0 || isHelpRequest(args)) {
            out.print(USAGE);
            return 0;
        }
        return switch (args[0]) {
            case "serve" -> serve(args, env, out, err);
            case "sync" -> sync(args, env, out, err);
            case "reef" -> reef(args, env, out, err);
            case "catalog" -> catalog(args, env, out, err);
            case "subscribe" -> subscribe(args, env, out, err);
            case "unsubscribe" -> unsubscribe(args, env, out, err);
            case "update" -> update(args, env, out, err);
            case "account" -> account(args, env, out, err);
            case "credential" -> credential(args, env, in, out, err);
            case "config" -> config(args, env, out, err);
            case "audit" -> audit(args, env, out, err);
            case "apikey" -> apiKey(args, env, out, err);
            case "local" -> local(args, env, out, err);
            case "mcp-stdio" -> new McpStdio().run(args, env, in, out, err);
            default -> {
                err.print(USAGE);
                yield 1;
            }
        };
    }

    private int reef(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 3 || !"import".equals(args[1])) {
            err.print(USAGE);
            return 1;
        }
        Path file = Path.of(args[2]);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            err.println("读不到引导文件。");
            return 1;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException ex) {
            err.println("读不到引导文件。");
            return 1;
        }
        if (size > ReefBootstrap.MAX_BYTES) {
            err.println("引导文件不能超过 64 KiB。");
            return 1;
        }
        byte[] body;
        try {
            body = Files.readAllBytes(file);
        } catch (IOException ex) {
            err.println("读不到引导文件。");
            return 1;
        }
        return withRuntime(env, runtime -> {
            runtime.importBootstrap(body, Actors.CLI);
            out.println("imported");
            return 0;
        });
    }

    private int serve(String[] args, Function<String, String> env, PrintStream out, PrintStream err) throws Exception {
        if (args.length != 1) {
            err.print(USAGE);
            return 1;
        }
        boolean handedOff = false;
        CrabServer server = null;
        try {
            server = CrabServer.start(env);
            out.println("listening on " + server.runtime().config().serverHost() + ":" + server.port());
            CountDownLatch latch = new CountDownLatch(1);
            CrabServer running = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.close();
                latch.countDown();
            }, "hermit-crab-shutdown"));
            handedOff = true;
            try {
                latch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                server.close();
                return 1;
            }
            return 0;
        } finally {
            if (!handedOff && server != null) {
                server.close();
            }
        }
    }

    private int sync(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.print(USAGE);
            return 1;
        }
        try (CrabRuntime runtime = open(env)) {
            runtime.loadInstalledConches();
            if (runtime.conchSync() == null) {
                err.println("reef.url is not configured");
                return 1;
            }
            SyncReport report = runtime.syncOnce(Actors.CLI).orElseThrow();
            if (report.notModified()) {
                out.println("not modified");
            }
            for (ConchOutcome outcome : report.conches()) {
                out.println(outcome.conch() + " " + text(outcome.version()) + " " + outcome.kind());
            }
            return 0;
        } catch (SyncException ex) {
            err.println(ex.clientError().map(SyncError::message).orElse(text(ex.getMessage())));
            return 1;
        }
    }

    private int catalog(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.print(USAGE);
            return 1;
        }
        try (CrabRuntime runtime = open(env)) {
            runtime.loadInstalledConches();
            ConchCatalog catalog = runtime.catalog();
            if (!catalog.configured()) {
                err.println("reef.url is not configured");
                return 1;
            }
            for (ConchCatalog.Entry entry : catalog.conches()) {
                out.println(catalogLine(entry));
            }
            return 0;
        } catch (SubscriptionException ex) {
            err.println(clientMessage(ex));
            return 1;
        }
    }

    private int subscribe(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.print(USAGE);
            return 1;
        }
        try (CrabRuntime runtime = open(env)) {
            runtime.loadInstalledConches();
            if (runtime.conchSync() == null) {
                err.println("reef.url is not configured");
                return 1;
            }
            ConchOutcome outcome = runtime.subscribe(args[1], Actors.CLI);
            if (outcome == null) {
                out.println(args[1] + " subscribed");
            } else {
                out.println(outcome.conch() + " " + text(outcome.version()) + " " + outcome.kind());
            }
            return 0;
        } catch (SubscriptionException ex) {
            err.println(clientMessage(ex));
            return 1;
        } catch (SyncException ex) {
            err.println(ex.clientError().map(SyncError::message).orElse(text(ex.getMessage())));
            return 1;
        }
    }

    private int unsubscribe(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.print(USAGE);
            return 1;
        }
        try (CrabRuntime runtime = open(env)) {
            runtime.loadInstalledConches();
            runtime.unsubscribe(args[1], Actors.CLI);
            out.println(args[1]);
            return 0;
        } catch (SubscriptionException ex) {
            err.println(clientMessage(ex));
            return 1;
        }
    }

    private int update(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.print(USAGE);
            return 1;
        }
        return withRuntime(env, runtime -> {
            try {
                runtime.loadInstalledConches();
                ConchOutcome outcome = runtime.update(args[1], Actors.CLI);
                String version = outcome == null || outcome.version() == null ? "" : outcome.version();
                out.println(args[1] + " " + version);
                return 0;
            } catch (SubscriptionException | UpdateException ex) {
                err.println(clientMessage(ex));
                return 1;
            }
        });
    }

    private static String catalogLine(ConchCatalog.Entry entry) {
        StringBuilder line = new StringBuilder();
        line.append(entry.conch()).append(' ').append(text(entry.version()));
        if (entry.subscribed()) {
            line.append(" subscribed");
        }
        if (entry.installed()) {
            line.append(" installed ").append(text(entry.installedVersion()));
        }
        if (entry.disabled()) {
            line.append(" disabled");
        }
        if (entry.localConflict()) {
            line.append(" local-conflict");
        }
        if (entry.updateAvailable()) {
            line.append(" update-available ").append(text(entry.latestVersion()));
        }
        line.append(' ').append(text(entry.title()));
        return line.toString();
    }

    private int account(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            err.print(USAGE);
            return 1;
        }
        String verb = args[1];
        String conch = args[2];
        return switch (verb) {
            case "list" -> {
                if (args.length != 3) {
                    err.print(USAGE);
                    yield 1;
                }
                yield withRuntime(env, runtime -> {
                    runtime.loadInstalledConches();
                    for (Account account : runtime.admin().listAccounts(conch)) {
                        out.println(account.defaultAccount() ? account.account() + " default" : account.account());
                    }
                    return 0;
                });
            }
            case "add", "remove", "default" -> {
                if (args.length != 4) {
                    err.print(USAGE);
                    yield 1;
                }
                String name = args[3];
                yield withRuntime(env, runtime -> {
                    runtime.loadInstalledConches();
                    switch (verb) {
                        case "add" -> runtime.admin().createAccount(conch, name, Actors.CLI);
                        case "remove" -> runtime.admin().deleteAccount(conch, name, Actors.CLI);
                        default -> runtime.admin().setDefault(conch, name, Actors.CLI);
                    }
                    out.println(name);
                    return 0;
                });
            }
            default -> {
                err.print(USAGE);
                yield 1;
            }
        };
    }

    private int credential(String[] args, Function<String, String> env, InputStream in, PrintStream out, PrintStream err)
            throws IOException {
        Parsed parsed = parse(args, err);
        if (parsed == null) {
            return 1;
        }
        if (parsed.positionals.size() < 2) {
            err.print(USAGE);
            return 1;
        }
        String verb = parsed.positionals.get(1);
        if ("list".equals(verb)) {
            if (parsed.positionals.size() != 4 || parsed.fromEnv != null || parsed.readOnly || parsed.all
                    || parsed.conches != null) {
                err.print(USAGE);
                return 1;
            }
            String conch = parsed.positionals.get(2);
            String account = parsed.positionals.get(3);
            return withRuntime(env, runtime -> {
                runtime.loadInstalledConches();
                runtime.admin().requireAccount(conch, account);
                for (String name : new TreeSet<>(runtime.admin().credentialNames(conch, account))) {
                    out.println(name);
                }
                return 0;
            });
        }
        if (!"set".equals(verb)) {
            err.print(USAGE);
            return 1;
        }
        if (parsed.positionals.size() > 5) {
            err.println("凭证不能写在命令行参数里。");
            return 1;
        }
        if (parsed.positionals.size() != 5 || parsed.readOnly || parsed.all
                || parsed.conches != null || parsed.actions != null) {
            err.print(USAGE);
            return 1;
        }
        String conch = parsed.positionals.get(2);
        String account = parsed.positionals.get(3);
        String field = parsed.positionals.get(4);
        CrabRuntime runtime = open(env);
        try {
            runtime.loadInstalledConches();
            ConchDoc doc = runtime.registry().find(conch).orElse(null);
            if (doc == null) {
                err.println("本机没有这个规则包。");
                return 1;
            }
            if (!runtime.admin().declaresCredential(doc, field)) {
                err.println("不认识的凭证字段。");
                return 1;
            }
            String secret = parsed.fromEnv == null ? readSecret(in, err) : envSecret(env, parsed.fromEnv, err);
            if (secret == null) {
                return 1;
            }
            try {
                runtime.admin().writeCredentials(doc, account, Map.of(field, secret), Actors.CLI);
            } catch (RuntimeException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains(secret)) {
                    err.println("凭证无效。");
                    return 1;
                }
                throw ex;
            }
            out.println(field);
            return 0;
        } finally {
            runtime.close();
        }
    }

    private int config(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            err.print(USAGE);
            return 1;
        }
        String verb = args[1];
        String conch = args[2];
        return switch (verb) {
            case "get" -> {
                if (args.length != 3 && args.length != 4) {
                    err.print(USAGE);
                    yield 1;
                }
                String key = args.length == 4 ? args[3] : null;
                yield withRuntime(env, runtime -> {
                    runtime.loadInstalledConches();
                    if (runtime.registry().find(conch).isEmpty()) {
                        err.println(missingConch(conch));
                        return 1;
                    }
                    Map<String, String> stored = runtime.admin().storedConfig(conch);
                    if (key == null) {
                        for (String name : new TreeSet<>(stored.keySet())) {
                            out.println(name + "=" + stored.get(name));
                        }
                        return 0;
                    }
                    if (!stored.containsKey(key)) {
                        err.println("config key is not set");
                        return 1;
                    }
                    out.println(stored.get(key));
                    return 0;
                });
            }
            case "set" -> {
                if (args.length != 5) {
                    err.print(USAGE);
                    yield 1;
                }
                yield withDeclaration(env, conch, err, (runtime, doc) -> {
                    runtime.admin().putConfig(doc, args[3], args[4], Actors.CLI);
                    out.println(args[3]);
                    return 0;
                });
            }
            case "unset" -> {
                if (args.length != 4) {
                    err.print(USAGE);
                    yield 1;
                }
                yield withDeclaration(env, conch, err, (runtime, doc) -> {
                    runtime.admin().deleteConfig(doc, args[3], Actors.CLI);
                    out.println(args[3]);
                    return 0;
                });
            }
            default -> {
                err.print(USAGE);
                yield 1;
            }
        };
    }

    private int local(String[] args, Function<String, String> env, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 2) {
            err.print(USAGE);
            return 1;
        }
        return switch (args[1]) {
            case "import" -> importLocal(args, env, out, err);
            case "list" -> listLocal(args, env, out, err);
            case "reload" -> reloadLocal(args, env, out, err);
            case "delete" -> deleteLocal(args, env, out, err);
            case "export" -> exportLocal(args, env, out, err);
            default -> {
                err.print(USAGE);
                yield 1;
            }
        };
    }

    private int importLocal(String[] args, Function<String, String> env, PrintStream out, PrintStream err) throws IOException {
        boolean replace = false;
        String fileArg = null;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if ("--replace".equals(arg)) {
                if (replace) {
                    err.println("--replace 重复了。");
                    return 1;
                }
                replace = true;
                continue;
            }
            if (arg.startsWith("--")) {
                err.println("不认识的选项 " + arg + "。");
                return 1;
            }
            if (fileArg != null) {
                err.print(USAGE);
                return 1;
            }
            fileArg = arg;
        }
        if (fileArg == null) {
            err.print(USAGE);
            return 1;
        }
        Path file = Path.of(fileArg);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            err.println("请提供 YAML 文件。");
            return 1;
        }
        if (Files.size(file) > LocalConches.MAX_BYTES) {
            err.println("YAML 不能超过 1 MiB。");
            return 1;
        }
        byte[] raw = Files.readAllBytes(file);
        boolean confirmed = replace;
        return withRuntime(env, runtime -> {
            runtime.loadInstalledConches();
            LocalConches.ImportResult result = runtime.localConches().importBytes(raw, Actors.CLI, confirmed);
            out.println(result.conch() + " " + result.version() + " " + (result.replaced() ? "已覆盖" : "已导入"));
            for (ValidationIssue issue : result.warnings()) {
                err.println(issue.code() + " " + issue.path());
            }
            return 0;
        });
    }

    private int listLocal(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.print(USAGE);
            return 1;
        }
        return withRuntime(env, runtime -> {
            runtime.loadInstalledConches();
            for (LocalConches.LocalPackage pkg : runtime.localConches().packages()) {
                if (pkg.broken()) {
                    out.println(pkg.conch() + " - " + pkg.file() + " 未载入 " + pkg.reason());
                } else {
                    out.println(pkg.conch() + " " + pkg.version() + " " + pkg.file());
                }
            }
            return 0;
        });
    }

    private int reloadLocal(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.print(USAGE);
            return 1;
        }
        return withRuntime(env, runtime -> {
            runtime.loadInstalledConches();
            for (LocalConches.FileResult result : runtime.localConches().reload(Actors.CLI)) {
                if (result.ok()) {
                    out.println(result.file() + " " + result.conch() + " " + result.version() + " ok");
                } else {
                    String name = result.conch() == null ? "-" : result.conch();
                    out.println(result.file() + " " + name + " - 未载入");
                    for (ValidationIssue issue : result.issues()) {
                        err.println(issue.message());
                    }
                }
            }
            return 0;
        });
    }

    private int deleteLocal(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            err.print(USAGE);
            return 1;
        }
        String conch = args[2];
        return withRuntime(env, runtime -> {
            runtime.loadInstalledConches();
            runtime.localConches().delete(conch, Actors.CLI);
            out.println(conch + " deleted");
            return 0;
        });
    }

    private int exportLocal(String[] args, Function<String, String> env, PrintStream out, PrintStream err) throws IOException {
        String conch;
        String outFile;
        if (args.length == 3) {
            conch = args[2];
            outFile = null;
        } else if (args.length == 5 && "--out".equals(args[3])) {
            conch = args[2];
            outFile = args[4];
        } else {
            err.print(USAGE);
            return 1;
        }
        if (outFile != null) {
            Path target = Path.of(outFile);
            if (Files.isSymbolicLink(target)) {
                err.println("输出路径不能是符号链接。");
                return 1;
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                err.println("输出目录不存在。");
                return 1;
            }
        }
        return withRuntime(env, runtime -> {
            runtime.loadInstalledConches();
            LocalConches.Export export = runtime.localConches().export(conch);
            if (outFile == null) {
                try {
                    out.write(export.bytes());
                    out.flush();
                } catch (IOException ex) {
                    err.println("没有写完输出文件。");
                    return 1;
                }
                return 0;
            }
            try {
                Files.write(Path.of(outFile), export.bytes());
            } catch (IOException ex) {
                err.println("没有写完输出文件。");
                return 1;
            }
            out.println(Path.of(outFile));
            return 0;
        });
    }

    private int audit(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length < 2 || !"list".equals(args[1])) {
            err.print(USAGE);
            return 1;
        }
        String conch = null;
        String account = null;
        Integer limit = null;
        Integer page = null;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (!"--conch".equals(arg) && !"--account".equals(arg) && !"--limit".equals(arg) && !"--page".equals(arg)) {
                err.print(USAGE);
                return 1;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                err.println("缺少 " + arg + " 的值。");
                return 1;
            }
            String value = args[++i];
            switch (arg) {
                case "--conch" -> {
                    if (conch != null) {
                        err.println(arg + " 重复了。");
                        return 1;
                    }
                    conch = value;
                }
                case "--account" -> {
                    if (account != null) {
                        err.println(arg + " 重复了。");
                        return 1;
                    }
                    account = value;
                }
                case "--limit" -> {
                    if (limit != null) {
                        err.println(arg + " 重复了。");
                        return 1;
                    }
                    limit = optionInt(value, AuditLog.LIMIT_OUT_OF_RANGE, err);
                    if (limit == null) {
                        return 1;
                    }
                }
                case "--page" -> {
                    if (page != null) {
                        err.println(arg + " 重复了。");
                        return 1;
                    }
                    page = optionInt(value, AuditLog.PAGE_OUT_OF_RANGE, err);
                    if (page == null) {
                        return 1;
                    }
                }
                default -> {
                    err.print(USAGE);
                    return 1;
                }
            }
        }
        int checkedLimit = limit == null ? AuditLog.DEFAULT_LIMIT : limit;
        int checkedPage = page == null ? 1 : page;
        String conchFilter = conch;
        String accountFilter = account;
        return withRuntime(env, runtime -> {
            for (AuditLog.Entry entry : runtime.configAudit(conchFilter, accountFilter, checkedPage, checkedLimit).entries()) {
                out.println(formatAudit(entry));
            }
            return 0;
        });
    }

    /** {@code message} is the Chinese sentence printed for a non-integer or a value below 1. */
    private static Integer optionInt(String value, String message, PrintStream err) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                err.println(message);
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            err.println(message);
            return null;
        }
    }

    private static boolean isHelpRequest(String[] args) {
        return args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]));
    }

    /** Installed conches only. The name is echoed when it matches the conch name rule. */
    private static String missingConch(String conch) {
        if (ConchValidator.isConchName(conch)) {
            return "本机没有这个规则包: " + conch;
        }
        return "本机没有这个规则包";
    }

    /**
     * One list line. Full official access prints {@code all=true} and omits the empty
     * conch and action sets. A narrower key prints {@code all=false} with those sets;
     * an empty dimension is {@code -}. {@code all} follows {@link ApiKeyScope#grantsAllOfficial()},
     * so a stored row that omits the flag still shows full access. The plaintext key is
     * not on {@link ApiKeySummary}.
     */
    private static String formatApiKey(ApiKeySummary key) {
        ApiKeyScope scope = key.scope();
        String line = key.id() + " " + key.prefix() + " " + key.name()
                + " all=" + scope.grantsAllOfficial();
        if (!scope.grantsAllOfficial()) {
            line += " conches=" + joinScope(scope.conches())
                    + " actions=" + joinScope(scope.actions());
        }
        line += " read-only=" + scope.readOnlyOnly();
        if (key.revokedAt() != null) {
            line += " revoked";
        }
        return line;
    }

    private static String joinScope(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return "-";
        }
        return String.join(",", new TreeSet<>(names));
    }

    private static String formatAudit(AuditLog.Entry entry) {
        String fields = entry.fields().isEmpty() ? "-" : String.join(",", entry.fields());
        return String.join(" ",
                entry.at().toString(),
                entry.actor(),
                entry.action(),
                dash(entry.origin()),
                dash(entry.conch()),
                dash(entry.account()),
                fields);
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private int apiKey(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        Parsed parsed = parse(args, err);
        if (parsed == null) {
            return 1;
        }
        if (parsed.positionals.size() < 2) {
            err.print(USAGE);
            return 1;
        }
        String verb = parsed.positionals.get(1);
        return switch (verb) {
            case "list" -> {
                if (parsed.positionals.size() != 2 || parsed.fromEnv != null || parsed.conches != null
                        || parsed.actions != null || parsed.readOnly || parsed.all) {
                    err.print(USAGE);
                    yield 1;
                }
                yield withRuntime(env, runtime -> {
                    for (ApiKeySummary key : runtime.admin().listApiKeys()) {
                        out.println(formatApiKey(key));
                    }
                    return 0;
                });
            }
            case "revoke" -> {
                if (parsed.positionals.size() != 3 || parsed.fromEnv != null || parsed.conches != null
                        || parsed.actions != null || parsed.readOnly || parsed.all) {
                    err.print(USAGE);
                    yield 1;
                }
                String id = parsed.positionals.get(2);
                yield withRuntime(env, runtime -> {
                    runtime.admin().revokeApiKey(id, Actors.CLI);
                    out.println(id);
                    return 0;
                });
            }
            case "create" -> {
                if (parsed.positionals.size() != 3 || parsed.fromEnv != null) {
                    err.print(USAGE);
                    yield 1;
                }
                String name = parsed.positionals.get(2);
                Set<String> conches = parsed.conches == null ? Set.of() : parsed.conches;
                Set<String> actions = parsed.actions == null ? Set.of() : parsed.actions;
                ApiKeyScope scope;
                try {
                    scope = ApiKeyScope.explicit(parsed.all, conches, actions, parsed.readOnly);
                } catch (IllegalArgumentException ex) {
                    err.println(clientMessage(ex));
                    yield 1;
                }
                yield withRuntime(env, runtime -> {
                    if (!scope.conches().isEmpty() || !scope.actions().isEmpty()) {
                        runtime.loadInstalledConches();
                    }
                    CreatedApiKey created = runtime.admin().createApiKey(name, scope, Actors.CLI);
                    out.println("id " + created.id());
                    out.println("prefix " + created.prefix());
                    out.println(created.plaintext());
                    return 0;
                });
            }
            default -> {
                err.print(USAGE);
                yield 1;
            }
        };
    }

    private static String readSecret(InputStream in, PrintStream err) throws IOException {
        err.println("凭证：");
        Console console = System.console();
        if (console != null && in == System.in) {
            char[] chars = console.readPassword();
            if (chars == null || chars.length == 0) {
                err.println("请提供凭证值。");
                return null;
            }
            try {
                return new String(chars);
            } finally {
                Arrays.fill(chars, '\0');
            }
        }
        String line = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        if (line == null || line.isEmpty()) {
            err.println("请提供凭证值。");
            return null;
        }
        return line;
    }

    private static String envSecret(Function<String, String> env, String name, PrintStream err) {
        String value = env.apply(name);
        if (value == null) {
            err.println("环境变量没有设置。");
            return null;
        }
        if (value.isEmpty()) {
            err.println("请提供凭证值。");
            return null;
        }
        return value;
    }

    private static Parsed parse(String[] args, PrintStream err) {
        ArrayList<String> positionals = new ArrayList<>();
        String fromEnv = null;
        Set<String> conches = null;
        Set<String> actions = null;
        boolean readOnly = false;
        boolean all = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--all".equals(arg)) {
                all = true;
                continue;
            }
            if ("--from-env".equals(arg) || "--conches".equals(arg) || "--actions".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    err.println("缺少 " + arg + " 的值。");
                    return null;
                }
                String value = args[++i];
                switch (arg) {
                    case "--from-env" -> fromEnv = value;
                    case "--conches" -> conches = split(value);
                    case "--actions" -> actions = split(value);
                    default -> {
                    }
                }
                continue;
            }
            if ("--read-only".equals(arg)) {
                readOnly = true;
                continue;
            }
            if (arg.startsWith("--")) {
                err.println("不认识的选项 " + arg + "。");
                return null;
            }
            positionals.add(arg);
        }
        return new Parsed(positionals, fromEnv, conches, actions, readOnly, all);
    }

    private static Set<String> split(String value) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return Set.copyOf(values);
    }

    private int withRuntime(Function<String, String> env, RuntimeTask task) {
        CrabRuntime runtime = open(env);
        try {
            return task.run(runtime);
        } finally {
            runtime.close();
        }
    }

    private int withDeclaration(Function<String, String> env, String conch, PrintStream err, DeclaredTask task) {
        return withRuntime(env, runtime -> {
            runtime.loadInstalledConches();
            ConchDoc doc = runtime.registry().find(conch).orElse(null);
            if (doc == null) {
                err.println("本机没有这个规则包。");
                return 1;
            }
            return task.run(runtime, doc);
        });
    }

    private static CrabRuntime open(Function<String, String> env) {
        CrabPaths paths = CrabPaths.fromEnvironment(env);
        CrabConfig config = CrabConfigLoader.load(paths);
        return CrabRuntime.open(paths, config, env);
    }

    private static String text(String value) {
        return value == null ? "-" : value;
    }

    private static String clientMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.length() > 200 || message.indexOf('\n') >= 0) {
            return "输入无效。";
        }
        return message;
    }

    private record Parsed(
            List<String> positionals,
            String fromEnv,
            Set<String> conches,
            Set<String> actions,
            boolean readOnly,
            boolean all
    ) {
    }

    @FunctionalInterface
    private interface RuntimeTask {
        int run(CrabRuntime runtime);
    }

    @FunctionalInterface
    private interface DeclaredTask {
        int run(CrabRuntime runtime, ConchDoc doc);
    }

}
