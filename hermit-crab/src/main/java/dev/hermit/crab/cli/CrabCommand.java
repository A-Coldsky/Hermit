package dev.hermit.crab.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParseException;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.config.ConfigFileException;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.config.CrabConfigLoader;
import dev.hermit.crab.engine.ShellRejectedException;
import dev.hermit.crab.mcp.McpStdio;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.secure.MasterKeyException;
import dev.hermit.crab.sync.ShellOutcome;
import dev.hermit.crab.sync.SyncReport;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.Actors;
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
            usage:
              crab serve
              crab sync
              crab account list <shell>
              crab account add|remove|default <shell> <name>
              crab credential set <shell> <account> <field> [--from-env VAR]
              crab credential list <shell> <account>
              crab config get <shell> [key]
              crab config set <shell> <key> <value>
              crab config unset <shell> <key>
              crab apikey create <name> [--shells a,b] [--actions shell.action] [--read-only]
              crab apikey list
              crab apikey revoke <id>
              crab mcp-stdio [--url http://127.0.0.1:7777/mcp] [--account NAME]
            """;

    public int run(String[] args, Function<String, String> env, InputStream in, PrintStream out, PrintStream err) {
        try {
            return dispatch(args == null ? new String[0] : args, env, in, out, err);
        } catch (DatabaseInUseException ex) {
            err.println(DatabaseInUseException.MESSAGE);
            return 2;
        } catch (ShellRejectedException ex) {
            err.println("shell was rejected");
            for (ValidationIssue issue : ex.issues()) {
                err.println(issue.code() + " " + issue.path());
            }
            return 1;
        } catch (ShellParseException ex) {
            err.println("installed shell could not be parsed");
            return 1;
        } catch (ConfigFileException ex) {
            err.println(ex.getMessage());
            return 1;
        } catch (AccountNotFoundException ex) {
            err.println(ex.account() == null ? "no default account" : "account not found");
            return 1;
        } catch (MasterKeyException ex) {
            err.println(clientMessage(ex));
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
        if (args.length == 0) {
            err.print(USAGE);
            return 1;
        }
        return switch (args[0]) {
            case "serve" -> serve(args, env, out, err);
            case "sync" -> sync(args, env, out, err);
            case "account" -> account(args, env, out, err);
            case "credential" -> credential(args, env, in, out, err);
            case "config" -> config(args, env, out, err);
            case "apikey" -> apiKey(args, env, out, err);
            case "mcp-stdio" -> new McpStdio().run(args, env, in, out, err);
            default -> {
                err.print(USAGE);
                yield 1;
            }
        };
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
            runtime.loadInstalledShells();
            if (runtime.shellSync() == null) {
                err.println("reef.url is not configured");
                return 1;
            }
            SyncReport report = runtime.syncOnce(Actors.CLI).orElseThrow();
            if (report.notModified()) {
                out.println("not modified");
            }
            for (ShellOutcome outcome : report.shells()) {
                out.println(outcome.shell() + " " + text(outcome.version()) + " " + outcome.kind());
            }
            return 0;
        }
    }

    private int account(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            err.print(USAGE);
            return 1;
        }
        String verb = args[1];
        String shell = args[2];
        return switch (verb) {
            case "list" -> {
                if (args.length != 3) {
                    err.print(USAGE);
                    yield 1;
                }
                yield withRuntime(env, runtime -> {
                    for (Account account : runtime.admin().listAccounts(shell)) {
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
                    switch (verb) {
                        case "add" -> runtime.admin().createAccount(shell, name, Actors.CLI);
                        case "remove" -> runtime.admin().deleteAccount(shell, name, Actors.CLI);
                        default -> runtime.admin().setDefault(shell, name, Actors.CLI);
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
            if (parsed.positionals.size() != 4 || parsed.fromEnv != null || parsed.readOnly || parsed.shells != null) {
                err.print(USAGE);
                return 1;
            }
            String shell = parsed.positionals.get(2);
            String account = parsed.positionals.get(3);
            return withRuntime(env, runtime -> {
                runtime.admin().requireAccount(shell, account);
                for (String name : new TreeSet<>(runtime.admin().credentialNames(shell, account))) {
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
            err.println("credential values must not be passed as arguments");
            return 1;
        }
        if (parsed.positionals.size() != 5 || parsed.readOnly || parsed.shells != null || parsed.actions != null) {
            err.print(USAGE);
            return 1;
        }
        String shell = parsed.positionals.get(2);
        String account = parsed.positionals.get(3);
        String field = parsed.positionals.get(4);
        CrabRuntime runtime = open(env);
        try {
            runtime.loadInstalledShells();
            ShellDoc doc = runtime.registry().find(shell).orElse(null);
            if (doc == null) {
                err.println("shell is not installed");
                return 1;
            }
            if (doc.credentials() == null || !doc.credentials().containsKey(field)) {
                err.println("unknown credential field");
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
                    err.println("invalid credential");
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
        String shell = args[2];
        return switch (verb) {
            case "get" -> {
                if (args.length != 3 && args.length != 4) {
                    err.print(USAGE);
                    yield 1;
                }
                String key = args.length == 4 ? args[3] : null;
                yield withRuntime(env, runtime -> {
                    Map<String, String> stored = runtime.admin().storedConfig(shell);
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
                yield withDeclaration(env, shell, err, (runtime, doc) -> {
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
                yield withDeclaration(env, shell, err, (runtime, doc) -> {
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
                if (parsed.positionals.size() != 2 || parsed.fromEnv != null || parsed.shells != null || parsed.actions != null || parsed.readOnly) {
                    err.print(USAGE);
                    yield 1;
                }
                yield withRuntime(env, runtime -> {
                    for (ApiKeySummary key : runtime.admin().listApiKeys()) {
                        out.println(key.id() + " " + key.prefix() + " " + key.name()
                                + (key.revokedAt() == null ? "" : " revoked"));
                    }
                    return 0;
                });
            }
            case "revoke" -> {
                if (parsed.positionals.size() != 3 || parsed.fromEnv != null || parsed.shells != null || parsed.actions != null || parsed.readOnly) {
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
                Set<String> shells = parsed.shells == null ? Set.of() : parsed.shells;
                Set<String> actions = parsed.actions == null ? Set.of() : parsed.actions;
                yield withRuntime(env, runtime -> {
                    if (!actions.isEmpty()) {
                        runtime.loadInstalledShells();
                    }
                    CreatedApiKey created = runtime.admin().createApiKey(
                            name, new ApiKeyScope(shells, actions, parsed.readOnly), Actors.CLI);
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
        err.println("credential:");
        Console console = System.console();
        if (console != null && in == System.in) {
            char[] chars = console.readPassword();
            if (chars == null || chars.length == 0) {
                err.println("credential value is required");
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
            err.println("credential value is required");
            return null;
        }
        return line;
    }

    private static String envSecret(Function<String, String> env, String name, PrintStream err) {
        String value = env.apply(name);
        if (value == null) {
            err.println("environment variable is not set");
            return null;
        }
        if (value.isEmpty()) {
            err.println("credential value is required");
            return null;
        }
        return value;
    }

    private static Parsed parse(String[] args, PrintStream err) {
        ArrayList<String> positionals = new ArrayList<>();
        String fromEnv = null;
        Set<String> shells = null;
        Set<String> actions = null;
        boolean readOnly = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--from-env".equals(arg) || "--shells".equals(arg) || "--actions".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    err.println("option value is required");
                    return null;
                }
                String value = args[++i];
                switch (arg) {
                    case "--from-env" -> fromEnv = value;
                    case "--shells" -> shells = split(value);
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
                err.println("unknown option");
                return null;
            }
            positionals.add(arg);
        }
        return new Parsed(positionals, fromEnv, shells, actions, readOnly);
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

    private int withDeclaration(Function<String, String> env, String shell, PrintStream err, DeclaredTask task) {
        return withRuntime(env, runtime -> {
            runtime.loadInstalledShells();
            ShellDoc doc = runtime.registry().find(shell).orElse(null);
            if (doc == null) {
                err.println("shell is not installed");
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
            return "invalid input";
        }
        return message;
    }

    private record Parsed(
            List<String> positionals,
            String fromEnv,
            Set<String> shells,
            Set<String> actions,
            boolean readOnly
    ) {
    }

    @FunctionalInterface
    private interface RuntimeTask {
        int run(CrabRuntime runtime);
    }

    @FunctionalInterface
    private interface DeclaredTask {
        int run(CrabRuntime runtime, ShellDoc doc);
    }

}
