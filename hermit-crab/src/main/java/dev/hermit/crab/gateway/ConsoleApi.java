package dev.hermit.crab.gateway;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.pkg.PackageReason;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.LoginPause;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.ConsoleSessions;
import dev.hermit.crab.runtime.CredentialMessages;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.DeclaredSettings;
import dev.hermit.crab.runtime.ReefBootstrapException;
import dev.hermit.crab.runtime.RequestOnlyException;
import dev.hermit.crab.runtime.StatusCodes;
import dev.hermit.crab.runtime.TrialRejected;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.report.FlushResult;
import dev.hermit.crab.sync.ConchSync;
import dev.hermit.crab.sync.SignatureDamage;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.upstream.McpRefresh;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.ApiKeySummary;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.ConsolePassword;
import dev.hermit.crab.store.DebugStateStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.upstream.McpOAuth;
import dev.hermit.crab.upstream.McpOAuthToken;
import dev.hermit.crab.sync.ConchCatalog;
import dev.hermit.crab.sync.CrabRelease;
import dev.hermit.crab.sync.LocalConchException;
import dev.hermit.crab.sync.LocalConches;
import dev.hermit.crab.sync.RuleDisabled;
import dev.hermit.crab.sync.CredentialDeclarationChange;
import dev.hermit.crab.sync.ConchOutcome;
import dev.hermit.crab.sync.OutcomeKind;
import dev.hermit.crab.sync.SubscriptionException;
import dev.hermit.crab.sync.UpdateException;
import dev.hermit.crab.sync.SyncError;
import dev.hermit.crab.sync.SyncException;
import dev.hermit.crab.sync.SyncReport;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.UploadedFile;

/**
 * Loopback console. Callers are already checked for the socket address and
 * {@code X-Hermit-Console: 1}. No API key is accepted or required.
 */
final class ConsoleApi {
    /** Preview and manual send when reports are off or Reef is not connected. HTTP 409. */
    static final String REPORTS_NOT_CONFIGURED = "上报未开启，或尚未连接 Reef。";

    private ConsoleApi() {
    }

    static void register(io.javalin.Javalin app, CrabRuntime runtime) {
        get(app, "/console/api/session", ctx -> session(runtime, ctx));
        post(app, "/console/api/session", ctx -> login(runtime, ctx));
        post(app, "/console/api/session/password", ctx -> changePassword(runtime, ctx));
        get(app, "/console/api/conches", ctx -> conches(runtime, ctx));
        get(app, "/console/api/conches/{conch}", ctx -> conch(runtime, ctx));
        get(app, "/console/api/conches/{conch}/accounts", ctx -> accounts(runtime, ctx));
        post(app, "/console/api/conches/{conch}/accounts", ctx -> createAccount(runtime, ctx));
        delete(app, "/console/api/conches/{conch}/accounts/{account}", ctx -> deleteAccount(runtime, ctx));
        post(app, "/console/api/conches/{conch}/accounts/{account}/default", ctx -> defaultAccount(runtime, ctx));
        get(app, "/console/api/conches/{conch}/accounts/{account}/credentials", ctx -> credentialNames(runtime, ctx));
        put(app, "/console/api/conches/{conch}/accounts/{account}/credentials", ctx -> writeCredentials(runtime, ctx));
        post(app, "/console/api/conches/{conch}/accounts/{account}/test-login", ctx -> testLogin(runtime, ctx));
        post(app, "/console/api/conches/{conch}/accounts/{account}/oauth/start", ctx -> oauthStart(runtime, ctx));
        delete(app, "/console/api/conches/{conch}/accounts/{account}/oauth", ctx -> oauthDisconnect(runtime, ctx));
        app.get("/console/oauth/callback", ctx -> oauthCallback(runtime, ctx));
        get(app, "/console/api/conches/{conch}/config", ctx -> config(runtime, ctx));
        put(app, "/console/api/conches/{conch}/config", ctx -> writeConfig(runtime, ctx));
        post(app, "/console/api/capabilities/{conch}/{action}/enable", ctx -> toggle(runtime, ctx, false));
        post(app, "/console/api/capabilities/{conch}/{action}/disable", ctx -> toggle(runtime, ctx, true));
        get(app, "/console/api/apikeys", ctx -> apiKeys(runtime, ctx));
        post(app, "/console/api/apikeys", ctx -> createApiKey(runtime, ctx));
        delete(app, "/console/api/apikeys/{id}", ctx -> revokeApiKey(runtime, ctx));
        get(app, "/console/api/calls", ctx -> calls(runtime, ctx));
        get(app, "/console/api/calls/{traceId}", ctx -> call(runtime, ctx));
        get(app, "/console/api/audit", ctx -> audit(runtime, ctx));
        get(app, "/console/api/debug", ctx -> debug(runtime, ctx));
        post(app, "/console/api/debug", ctx -> setDebug(runtime, ctx));
        post(app, "/console/api/sync", ctx -> sync(runtime, ctx));
        get(app, "/console/api/sync", ctx -> syncStatus(runtime, ctx));
        get(app, "/console/api/catalog", ctx -> catalog(runtime, ctx));
        post(app, "/console/api/conches/{conch}/subscription", ctx -> subscribe(runtime, ctx));
        post(app, "/console/api/conches/{conch}/update", ctx -> update(runtime, ctx));
        post(app, "/console/api/conches/{conch}/rollback", ctx -> rollback(runtime, ctx));
        delete(app, "/console/api/conches/{conch}/subscription", ctx -> unsubscribe(runtime, ctx));
        get(app, "/console/api/reef", ctx -> reef(runtime, ctx));
        post(app, "/console/api/reef/bootstrap", ctx -> importReef(runtime, ctx));
        get(app, "/console/api/reports/settings", ctx -> reportSettings(runtime, ctx));
        put(app, "/console/api/reports/settings", ctx -> updateReportSettings(runtime, ctx));
        get(app, "/console/api/reports/preview", ctx -> reportPreview(runtime, ctx));
        post(app, "/console/api/reports/send", ctx -> reportSend(runtime, ctx));
        get(app, "/console/api/local-conches", ctx -> listLocal(runtime, ctx));
        post(app, "/console/api/local-conches", ctx -> importLocal(runtime, ctx));
        post(app, "/console/api/local-conches/reload", ctx -> reloadLocal(runtime, ctx));
        delete(app, "/console/api/local-conches/{conch}", ctx -> deleteLocal(runtime, ctx));
        get(app, "/console/api/local-conches/{conch}/export", ctx -> exportLocal(runtime, ctx));
        post(app, "/console/api/local-conches/{conch}/actions/{action}/run", ctx -> runLocal(runtime, ctx));
        post(app, "/console/api/local-conches/{conch}/mcp/refresh", ctx -> refreshLocalMcp(runtime, ctx));
    }

    private static void conches(CrabRuntime runtime, Context ctx) {
        Map<String, ConchCatalog.Entry> catalog = catalogByName(runtime);
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (ConchDoc doc : runtime.registry().snapshot().values()) {
            List<Account> accounts = runtime.admin().listAccounts(doc.conch());
            boolean loginPaused = loginPaused(runtime, doc, accounts);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("conch", doc.conch());
            row.put("version", doc.version().toString());
            row.put("title", text(doc.title()));
            row.put("driver", driver(doc));
            row.put("capabilityCount", doc.actions().size());
            row.put("subscribed", runtime.subscribed(doc.conch()));
            row.put("accountCount", accounts.size());
            row.put("needsConfiguration", needsConfiguration(runtime, doc, accounts, loginPaused));
            row.put("loginPaused", loginPaused);
            row.put("source", source(runtime, doc.conch()));
            putReefDisabled(runtime, doc.conch(), row);
            putUpdateFields(catalog.get(doc.conch()), row);
            putPrevious(runtime, doc.conch(), row);
            rows.add(row);
        }
        appendDisabled(runtime, rows);
        GatewayResponses.writeData(ctx, Map.of("conches", rows));
    }

    private static void conch(CrabRuntime runtime, Context ctx) {
        String name = ctx.pathParam("conch");
        ConchDoc doc = runtime.registry().find(name).orElse(null);
        if (doc == null) {
            ConchSync sync = runtime.conchSync();
            ConchSync.UntrustedSignature damaged = sync == null ? null : sync.untrustedSignature(name).orElse(null);
            if (damaged != null) {
                GatewayResponses.writeData(ctx, untrustedConch(damaged));
                return;
            }
            ConchSync.DisabledRule disabled = sync == null ? null : sync.disabledRule(name).orElse(null);
            if (disabled != null) {
                GatewayResponses.writeData(ctx, disabledConch(runtime, disabled));
                return;
            }
            LocalConches.LocalPackage local = localPackage(runtime, name);
            if (local != null && local.ruleDisabled()) {
                GatewayResponses.writeData(ctx, localDisabledConch(runtime, local));
                return;
            }
            throw new IllegalArgumentException("conch not found");
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", doc.conch());
        body.put("version", doc.version().toString());
        body.put("title", text(doc.title()));
        body.put("description", text(doc.description()));
        putPackageShape(runtime, doc, body);
        List<Account> accounts = runtime.admin().listAccounts(doc.conch());
        boolean paused = loginPaused(runtime, doc, accounts);
        body.put("subscribed", runtime.subscribed(doc.conch()));
        body.put("needsConfiguration", needsConfiguration(runtime, doc, accounts, paused));
        body.put("loginPaused", paused);
        body.put("source", source(runtime, doc.conch()));
        putReefDisabled(runtime, doc.conch(), body);
        putUpdateFields(catalogByName(runtime).get(doc.conch()), body);
        putPrevious(runtime, doc.conch(), body);
        body.put("ruleDisabled", false);
        GatewayResponses.writeData(ctx, body);
    }

    /**
     * Subscribed, but the on-disk zip fails signature verification and is not loaded.
     * Capabilities stay empty: the untrusted package is not a callable install.
     */
    private static Map<String, Object> untrustedConch(ConchSync.UntrustedSignature damaged) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", damaged.conch());
        body.put("version", null);
        body.put("title", damaged.title());
        body.put("description", damaged.description());
        body.put("driver", null);
        body.put("credentials", List.of());
        body.put("config", List.of());
        body.put("capabilities", List.of());
        body.put("subscribed", true);
        body.put("needsConfiguration", false);
        body.put("loginPaused", false);
        body.put("source", "REEF");
        body.put("reefDisabled", false);
        body.put("reefDisabledAt", null);
        body.put("installed", false);
        body.put("repairRequired", true);
        body.put("repairReason", PackageReason.BAD_SIGNATURE.name());
        body.put("message", SignatureDamage.DETAIL_MESSAGE);
        body.put("updateAvailable", false);
        body.put("latestVersion", damaged.latestVersion());
        body.put("newRequiredCredentials", List.of());
        body.put("ruleDisabled", false);
        body.put("previousVersion", null);
        body.put("previousDisabled", false);
        body.put("previousPresent", false);
        return body;
    }

    /** Signature verified, files kept, but this version cannot be called until it is replaced or fixed. */
    private static Map<String, Object> disabledConch(CrabRuntime runtime, ConchSync.DisabledRule disabled) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", disabled.conch());
        body.put("version", disabled.version());
        body.put("title", disabled.title());
        body.put("description", text(disabled.description()));
        body.put("driver", null);
        body.put("credentials", List.of());
        body.put("config", List.of());
        body.put("capabilities", List.of());
        body.put("declarationsLoaded", false);
        ConchDoc readable = runtime.conchSync() == null
                ? null
                : runtime.conchSync().readableInstalled(disabled.conch()).orElse(null);
        if (readable != null) {
            putPackageShape(runtime, readable, body);
        }
        body.put("subscribed", true);
        body.put("needsConfiguration", false);
        body.put("loginPaused", false);
        body.put("source", "REEF");
        body.put("reefDisabled", false);
        body.put("reefDisabledAt", null);
        body.put("installed", true);
        body.put("repairRequired", false);
        body.put("repairReason", null);
        body.put("message", disabled.message());
        body.put("ruleDisabled", true);
        body.put("previousVersion", disabled.previousVersion());
        body.put("previousDisabled", disabled.previousDisabled());
        body.put("previousPresent", disabled.previousPresent());
        body.put("fixHint", disabled.fixHint());
        ConchCatalog.Entry row = catalogByName(runtime).get(disabled.conch());
        body.put("updateAvailable", row != null && row.updateAvailable());
        body.put("latestVersion", row != null && row.latestVersion() != null ? row.latestVersion() : disabled.latestVersion());
        body.put("newRequiredCredentials", row == null ? List.of() : row.newRequiredCredentials());
        return body;
    }

    private static void appendDisabled(CrabRuntime runtime, ArrayList<Map<String, Object>> rows) {
        ConchSync sync = runtime.conchSync();
        if (sync == null) {
            return;
        }
        for (ConchSync.DisabledRule disabled : sync.disabledRules()) {
            boolean listed = false;
            for (Map<String, Object> row : rows) {
                if (disabled.conch().equals(row.get("conch"))) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                rows.add(disabledSummary(runtime, disabled));
            }
        }
    }

    private static Map<String, Object> disabledSummary(CrabRuntime runtime, ConchSync.DisabledRule disabled) {
        List<Account> accounts = runtime.admin().listAccounts(disabled.conch());
        ConchCatalog.Entry catalog = catalogByName(runtime).get(disabled.conch());
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("conch", disabled.conch());
        row.put("version", disabled.version());
        row.put("title", text(disabled.title()));
        ConchDoc readable = runtime.conchSync() == null
                ? null
                : runtime.conchSync().readableInstalled(disabled.conch()).orElse(null);
        row.put("driver", readable == null ? "http" : driver(readable));
        row.put("capabilityCount", readable == null ? 0 : readable.actions().size());
        row.put("message", disabled.message());
        row.put("subscribed", true);
        row.put("accountCount", accounts.size());
        row.put("needsConfiguration", false);
        row.put("loginPaused", false);
        row.put("source", "REEF");
        row.put("reefDisabled", false);
        row.put("reefDisabledAt", null);
        row.put("updateAvailable", catalog != null && catalog.updateAvailable());
        row.put("latestVersion", catalog != null && catalog.latestVersion() != null
                ? catalog.latestVersion() : disabled.latestVersion());
        row.put("newRequiredCredentials", catalog == null ? List.of() : catalog.newRequiredCredentials());
        row.put("ruleDisabled", true);
        row.put("previousVersion", disabled.previousVersion());
        row.put("previousDisabled", disabled.previousDisabled());
        row.put("previousPresent", disabled.previousPresent());
        row.put("fixHint", disabled.fixHint());
        return row;
    }

    private static LocalConches.LocalPackage localPackage(CrabRuntime runtime, String name) {
        if (runtime.localConches() == null || name == null) {
            return null;
        }
        for (LocalConches.LocalPackage pkg : runtime.localConches().packages()) {
            if (name.equals(pkg.conch())) {
                return pkg;
            }
        }
        return null;
    }

    private static Map<String, Object> localDisabledConch(CrabRuntime runtime, LocalConches.LocalPackage pkg) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", pkg.conch());
        body.put("version", pkg.version());
        body.put("title", pkg.conch());
        body.put("description", "");
        body.put("driver", null);
        body.put("credentials", List.of());
        body.put("config", List.of());
        body.put("capabilities", List.of());
        body.put("declarationsLoaded", false);
        ConchDoc readable = runtime.localConches() == null
                ? null
                : runtime.localConches().readableDoc(pkg.conch()).orElse(null);
        if (readable != null) {
            putPackageShape(runtime, readable, body);
            body.put("version", readable.version().toString());
            body.put("title", text(readable.title()));
            body.put("description", text(readable.description()));
        }
        body.put("subscribed", false);
        body.put("needsConfiguration", false);
        body.put("loginPaused", false);
        body.put("source", "LOCAL");
        body.put("reefDisabled", false);
        body.put("reefDisabledAt", null);
        body.put("installed", true);
        body.put("repairRequired", false);
        body.put("repairReason", null);
        body.put("message", pkg.reason());
        body.put("ruleDisabled", true);
        body.put("previousVersion", null);
        body.put("previousDisabled", false);
        body.put("previousPresent", false);
        body.put("fixHint", RuleDisabled.fixHint(true, false));
        body.put("updateAvailable", false);
        body.put("latestVersion", null);
        body.put("newRequiredCredentials", List.of());
        return body;
    }

    private static void putPackageShape(CrabRuntime runtime, ConchDoc doc, Map<String, Object> body) {
        body.put("driver", driver(doc));
        body.put("declarationsLoaded", true);
        body.put("credentials", credentialDecls(doc));
        body.put("config", configDecls(doc));
        ArrayList<Map<String, Object>> capabilities = new ArrayList<>();
        for (Action action : doc.actions().values()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("action", action.name());
            row.put("title", text(action.title()));
            row.put("description", text(action.description()));
            row.put("method", CapabilityHttp.method(action).name());
            row.put("readOnly", CapabilityHttp.readOnly(action));
            row.put("disabled", runtime.disabled(doc.conch(), action.name()));
            capabilities.add(row);
        }
        body.put("capabilities", capabilities);
    }

    private static void putPrevious(CrabRuntime runtime, String conch, Map<String, Object> row) {
        ConchSync.PreviousState previous = runtime.previousState(conch);
        row.put("previousVersion", previous.version());
        row.put("previousDisabled", previous.disabled());
        row.put("previousPresent", previous.present());
    }

    private static void putRuleHint(CrabRuntime runtime, String conch, Map<String, Object> row) {
        putPrevious(runtime, conch, row);
        ConchSync.PreviousState previous = runtime.previousState(conch);
        Object update = row.get("updateAvailable");
        boolean updateAvailable = update instanceof Boolean flag && flag;
        row.put("fixHint", RuleDisabled.fixHint(false, previous.rollbackable(), updateAvailable));
    }

    private static void accounts(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (Account account : runtime.admin().listAccounts(doc.conch())) {
            List<String> missing = runtime.admin().missingCredentials(doc, account.account());
            boolean unreadable = runtime.admin().credentialsUnreadable(doc.conch(), account.account());
            boolean paused = runtime.admin().loginPaused(doc.conch(), account.account());
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", account.account());
            row.put("default", account.defaultAccount());
            row.put("needsConfiguration", !missing.isEmpty() || unreadable);
            row.put("missingFields", missing);
            ArrayList<String> labels = new ArrayList<>();
            for (String field : missing) {
                labels.add(CredentialMessages.display(field, doc.credentials()));
            }
            row.put("missingLabels", labels);
            row.put("credentialsUnreadable", unreadable);
            row.put("credentialMessage", unreadable ? CredentialDecryptException.MESSAGE : null);
            row.put("loginPaused", paused);
            row.put("loginPauseReason", paused ? LoginPause.REASON : null);
            row.put("loginPauseRecovery", paused ? LoginPause.RECOVERY : null);
            row.put("oauth", oauthStatus(runtime, doc, account.account()));
            rows.add(row);
        }
        GatewayResponses.writeData(ctx, Map.of("accounts", rows));
    }

    private static void createAccount(CrabRuntime runtime, Context ctx) throws Exception {
        ConchDoc doc = requireConch(runtime, ctx);
        String name = requiredText(object(ctx), "name");
        Account account = runtime.admin().createAccount(doc.conch(), name, Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of("name", account.account(), "default", account.defaultAccount()));
    }

    private static void deleteAccount(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        runtime.admin().deleteAccount(doc.conch(), ctx.pathParam("account"), Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of("deleted", true));
    }

    private static void defaultAccount(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        String name = ctx.pathParam("account");
        runtime.admin().setDefault(doc.conch(), name, Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of("name", name, "default", true));
    }

    private static void credentialNames(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        String account = ctx.pathParam("account");
        runtime.admin().requireAccount(doc.conch(), account);
        ArrayList<String> names = new ArrayList<>(runtime.admin().credentialNames(doc.conch(), account));
        names.remove(McpOAuthToken.FIELD);
        names.sort(String::compareTo);
        GatewayResponses.writeData(ctx, Map.of("fields", names));
    }

    private static void writeCredentials(CrabRuntime runtime, Context ctx) throws Exception {
        ConchDoc doc = requireConch(runtime, ctx);
        String account = ctx.pathParam("account");
        runtime.admin().requireAccount(doc.conch(), account);
        Map<String, String> fields = stringMap(object(ctx));
        if (fields.isEmpty()) {
            List<String> required = DeclaredSettings.missingCredentials(doc, Set.of());
            if (required.isEmpty()) {
                throw new RequestOnlyException(400, ErrorCode.INVALID_INPUT, "没有要保存的凭证字段。");
            }
            throw new RequestOnlyException(400, ErrorCode.INVALID_INPUT,
                    CredentialMessages.missing(account, required, doc.credentials()));
        }
        if (runtime.admin().credentialsUnreadable(doc.conch(), account)) {
            ArrayList<String> pending = new ArrayList<>();
            for (String name : new TreeSet<>(runtime.admin().credentialNames(doc.conch(), account))) {
                if (McpOAuthToken.FIELD.equals(name)) {
                    continue;
                }
                String value = fields.get(name);
                if (value == null || value.isEmpty()) {
                    pending.add(name);
                }
            }
            if (!pending.isEmpty()) {
                throw new IllegalArgumentException(CredentialDecryptException.MESSAGE + ": " + String.join(", ", pending));
            }
        }
        LinkedHashSet<String> present = new LinkedHashSet<>(runtime.admin().credentialNames(doc.conch(), account));
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getValue().isEmpty()) {
                present.remove(entry.getKey());
            } else {
                present.add(entry.getKey());
            }
        }
        List<String> missing = DeclaredSettings.missingCredentials(doc, present);
        if (!missing.isEmpty()) {
            throw new RequestOnlyException(400, ErrorCode.INVALID_INPUT,
                    CredentialMessages.missing(account, missing, doc.credentials()));
        }
        runtime.admin().writeCredentials(doc, account, fields, Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of("updated", true));
    }

    private static void testLogin(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        ExecutionResult result = runtime.admin().testLogin(doc, ctx.pathParam("account"), GatewayResponses.trace(ctx), Actors.CONSOLE);
        if (result.success()) {
            GatewayResponses.writeData(ctx, result.data());
            return;
        }
        ErrorCode code = result.error() == null ? ErrorCode.INTERNAL : result.error().code();
        String message = result.error() == null ? "internal error" : result.error().message();
        GatewayResponses.writeRequestError(ctx, StatusCodes.of(code), code, message == null ? "" : message);
    }

    private static void oauthStart(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        McpOAuth.Start start = runtime.oauth().start(
                origin(runtime, doc.conch()),
                doc,
                ctx.pathParam("account"),
                redirectUri(ctx),
                System.nanoTime() + McpOAuth.START_BUDGET_NANOS);
        if (!start.ok()) {
            GatewayResponses.writeError(ctx, StatusCodes.of(start.code()), start.code(), start.message());
            return;
        }
        GatewayResponses.writeData(ctx, Map.of("authorizationUrl", start.authorizationUrl()));
    }

    private static void oauthDisconnect(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        McpOAuth.Outcome outcome = runtime.oauth().disconnect(
                origin(runtime, doc.conch()), doc, ctx.pathParam("account"));
        if (!outcome.ok()) {
            GatewayResponses.writeError(ctx, StatusCodes.of(outcome.code()), outcome.code(), outcome.message());
            return;
        }
        LinkedHashMap<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("connected", false);
        oauth.put("expiresAt", null);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("oauth", oauth);
        GatewayResponses.writeData(ctx, body);
    }

    private static void oauthCallback(CrabRuntime runtime, Context ctx) {
        McpOAuth.Callback callback;
        try {
            callback = runtime.oauth().callback(ctx.queryParam("code"), ctx.queryParam("state"), ctx.queryParam("error"));
        } catch (RuntimeException ex) {
            callback = McpOAuth.Callback.fail(McpOAuth.CALLBACK_FAILED);
        }
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("X-Content-Type-Options", "nosniff");
        GatewayResponses.writeBytes(
                ctx,
                callback.connected() ? 200 : 400,
                "text/html; charset=utf-8",
                "no-store",
                McpOAuth.page(callback));
    }

    private static Object oauthStatus(CrabRuntime runtime, ConchDoc doc, String account) {
        McpOAuth.Status status = runtime.oauth().status(origin(runtime, doc.conch()), doc, account);
        if (!status.applicable()) {
            return null;
        }
        LinkedHashMap<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("connected", status.connected());
        oauth.put("expiresAt", status.expiresAt() == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(status.expiresAt()));
        return oauth;
    }

    private static String redirectUri(Context ctx) {
        String host = ctx.req().getLocalAddr();
        int port = ctx.req().getLocalPort();
        if (host == null || !Loopback.address(host)) {
            host = "127.0.0.1";
        } else if ("::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
            host = "[::1]";
        } else if ("::ffff:127.0.0.1".equalsIgnoreCase(host)) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + port + "/console/oauth/callback";
    }

    private static Origin origin(CrabRuntime runtime, String conch) {
        return runtime.admin().originOf(conch);
    }

    private static void config(CrabRuntime runtime, Context ctx) {
        ConchDoc doc = requireConch(runtime, ctx);
        Map<String, String> stored = runtime.admin().storedConfig(doc.conch());
        List<String> missing = DeclaredSettings.missingConfig(doc, stored);
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigField> entry : doc.config().entrySet()) {
            ConfigField field = entry.getValue();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("type", text(field.type()));
            row.put("label", text(field.label()));
            row.put("required", field.required());
            row.put("description", text(field.description()));
            row.put("options", field.options());
            row.put("value", stored.get(entry.getKey()));
            row.put("defaultValue", field.defaultValue());
            row.put("missing", missing.contains(entry.getKey()));
            fields.put(entry.getKey(), row);
        }
        GatewayResponses.writeData(ctx, Map.of("fields", fields));
    }

    private static void writeConfig(CrabRuntime runtime, Context ctx) throws Exception {
        ConchDoc doc = requireConch(runtime, ctx);
        for (Map.Entry<String, String> entry : stringMap(object(ctx)).entrySet()) {
            runtime.admin().putConfig(doc, entry.getKey(), entry.getValue(), Actors.CONSOLE);
        }
        GatewayResponses.writeData(ctx, Map.of("updated", true));
    }

    private static void toggle(CrabRuntime runtime, Context ctx, boolean disabled) {
        String conch = ctx.pathParam("conch");
        String action = ctx.pathParam("action");
        runtime.setCapabilityDisabled(conch, action, disabled, Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of(
                "conch", conch,
                "action", action,
                "disabled", runtime.disabled(conch, action)));
    }

    private static void apiKeys(CrabRuntime runtime, Context ctx) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (ApiKeySummary key : runtime.admin().listApiKeys()) {
            rows.add(summary(key));
        }
        GatewayResponses.writeData(ctx, Map.of("apiKeys", rows));
    }

    private static final Set<String> API_KEY_FIELDS = Set.of("name", "readOnly", "all", "conches", "actions");

    private static void createApiKey(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = object(ctx);
        rejectUnknownFields(body, API_KEY_FIELDS);
        String name = requiredText(body, "name");
        boolean readOnly = bool(body, "readOnly", false);
        boolean all = bool(body, "all", false);
        Set<String> conches = stringSet(body.get("conches"));
        Set<String> actions = stringSet(body.get("actions"));
        CreatedApiKey created = runtime.admin().createApiKey(
                name, ApiKeyScope.explicit(all, conches, actions, readOnly), Actors.CONSOLE);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("id", created.id());
        data.put("name", created.name());
        data.put("prefix", created.prefix());
        data.put("plaintext", created.plaintext());
        data.put("scope", scope(created.scope()));
        data.put("createdAt", created.createdAt());
        GatewayResponses.writeData(ctx, data);
    }

    private static void revokeApiKey(CrabRuntime runtime, Context ctx) {
        runtime.admin().revokeApiKey(ctx.pathParam("id"), Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of("revoked", true));
    }

    private static void calls(CrabRuntime runtime, Context ctx) {
        int limit = limit(ctx);
        List<CallLogStore.CallSummary> rows = runtime.callLogs().list(
                ctx.queryParam("conch"), ctx.queryParam("action"), ctx.queryParam("channel"), limit);
        ArrayList<Map<String, Object>> calls = new ArrayList<>();
        for (CallLogStore.CallSummary row : rows) {
            calls.add(summary(row));
        }
        GatewayResponses.writeData(ctx, Map.of("calls", calls));
    }

    private static void call(CrabRuntime runtime, Context ctx) {
        CallLogStore.CallDetail detail = runtime.callLogs().findByTraceId(ctx.pathParam("traceId")).orElse(null);
        if (detail == null) {
            GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            return;
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>(summary(detail.call()));
        ArrayList<Map<String, Object>> steps = new ArrayList<>();
        for (CallLogStore.StepRow step : detail.steps()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("index", step.index());
            row.put("type", step.type());
            row.put("durationMs", step.durationMs());
            row.put("method", step.method());
            row.put("path", step.path());
            row.put("status", step.status());
            row.put("extractCount", step.extractCount());
            steps.add(row);
        }
        body.put("steps", steps);
        body.put("devTrace", devTrace(runtime, detail.call().traceId()));
        GatewayResponses.writeData(ctx, body);
    }

    private static void refreshLocalMcp(CrabRuntime runtime, Context ctx) {
        String account = ctx.queryParam("account");
        Optional<String> selected = account == null || account.isBlank() ? Optional.empty() : Optional.of(account);
        McpRefresh.Outcome outcome = runtime.refreshLocalMcp(
                ctx.pathParam("conch"), selected, Actors.CONSOLE, GatewayResponses.trace(ctx));
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("version", outcome.version());
        body.put("added", outcome.added());
        body.put("removed", outcome.removed());
        body.put("changed", outcome.changed());
        body.put("traceId", outcome.traceId());
        GatewayResponses.writeData(ctx, body);
    }

    private static void runLocal(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = object(ctx);
        rejectUnknownFields(body, Set.of("account", "input"));
        Optional<String> account = optionalAccount(body.get("account"));
        Map<String, Object> input = inputObject(body.get("input"));
        try {
            ExecutionResult result = runtime.trialLocal(
                    ctx.pathParam("conch"),
                    ctx.pathParam("action"),
                    account,
                    input,
                    GatewayResponses.trace(ctx));
            if (result.success()) {
                GatewayResponses.writeData(ctx, result.data());
                return;
            }
            ErrorCode code = result.error() == null ? ErrorCode.INTERNAL : result.error().code();
            String message = result.error() == null ? "内部错误" : result.error().message();
            GatewayResponses.writeError(ctx, StatusCodes.of(code), code, message);
        } catch (TrialRejected ex) {
            if (ex.requestOnly()) {
                GatewayResponses.writeRequestError(ctx, ex.status(), ex.code(), ex.getMessage());
                return;
            }
            runtime.noteRejectedTrial(GatewayResponses.trace(ctx));
            GatewayResponses.writeError(ctx, ex.status(), ex.code(), ex.getMessage(), false);
        }
    }

    private static Object devTrace(CrabRuntime runtime, String traceId) {
        return runtime.devTraces().find(traceId).map(trace -> {
            try {
                return ApiJson.objectMap(trace.body().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                return null;
            }
        }).orElse(null);
    }

    private static Optional<String> optionalAccount(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual()) {
            throw new RequestOnlyException(400, ErrorCode.INVALID_INPUT, CrabRuntime.TRIAL_ACCOUNT_INVALID);
        }
        String name = node.textValue();
        if (name.isBlank()) {
            throw new RequestOnlyException(400, ErrorCode.INVALID_INPUT, CrabRuntime.TRIAL_ACCOUNT_INVALID);
        }
        return Optional.of(name);
    }

    private static Map<String, Object> inputObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("input must be an object");
        }
        return ApiJson.objectMap(ApiJson.bytes(node.toString()));
    }

    private static void audit(CrabRuntime runtime, Context ctx) {
        AuditLog.Page page = runtime.configAudit(
                ctx.queryParam("conch"),
                ctx.queryParam("account"),
                pageNumber(ctx),
                auditLimit(ctx));
        ArrayList<Map<String, Object>> entries = new ArrayList<>();
        for (AuditLog.Entry entry : page.entries()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("at", entry.at());
            row.put("actor", entry.actor());
            row.put("action", entry.action());
            row.put("origin", entry.origin());
            row.put("conch", entry.conch());
            row.put("account", entry.account());
            row.put("fields", entry.fields());
            entries.add(row);
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("entries", entries);
        body.put("page", page.page());
        body.put("limit", page.limit());
        body.put("total", page.total());
        GatewayResponses.writeData(ctx, body);
    }

    private static void session(CrabRuntime runtime, Context ctx) {
        boolean required = runtime.consolePassword().isSet();
        boolean authenticated = !required || runtime.consoleSessions().valid(ctx.cookie(ConsoleSessions.COOKIE));
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("required", required);
        body.put("authenticated", authenticated);
        GatewayResponses.writeData(ctx, body);
    }

    private static void login(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = object(ctx);
        rejectUnknownFields(body, Set.of("password"));
        String password = textual(body, "password");
        switch (runtime.consolePassword().verify(password)) {
            case UNSET -> GatewayResponses.writeError(ctx, 409, ErrorCode.NOT_CONFIGURED, ConsolePassword.UNSET_MESSAGE);
            case REJECTED -> {
                runtime.recordConsoleLogin(false);
                GatewayResponses.writeError(ctx, 401, ErrorCode.AUTH_FAILED, ConsolePassword.FAILURE_MESSAGE);
            }
            case ACCEPTED -> {
                runtime.recordConsoleLogin(true);
                ctx.header("Set-Cookie", ConsoleSessions.setCookie(runtime.consoleSessions().issue()));
                GatewayResponses.writeData(ctx, Map.of("ok", true));
            }
        }
    }

    private static void changePassword(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = object(ctx);
        rejectUnknownFields(body, Set.of("current", "next"));
        if (!runtime.consolePassword().isSet()) {
            GatewayResponses.writeError(ctx, 409, ErrorCode.NOT_CONFIGURED, ConsolePassword.UNSET_MESSAGE);
            return;
        }
        String next = textual(body, "next");
        if (next.length() < ConsolePassword.MIN_LENGTH || next.length() > ConsolePassword.MAX_LENGTH) {
            throw new IllegalArgumentException(ConsolePassword.LENGTH_MESSAGE);
        }
        String current = textual(body, "current");
        if (runtime.consolePassword().verify(current) != ConsolePassword.Result.ACCEPTED) {
            GatewayResponses.writeError(ctx, 401, ErrorCode.AUTH_FAILED, ConsolePassword.FAILURE_MESSAGE);
            return;
        }
        runtime.replaceConsolePassword(next);
        GatewayResponses.writeData(ctx, Map.of("ok", true));
    }

    private static void debug(CrabRuntime runtime, Context ctx) {
        GatewayResponses.writeData(ctx, debug(runtime.debug().get()));
    }

    private static void setDebug(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = object(ctx);
        if (!body.has("enabled") || !body.get("enabled").isBoolean()) {
            throw new IllegalArgumentException("enabled is required");
        }
        GatewayResponses.writeData(ctx, debug(runtime.debug().setEnabled(body.get("enabled").booleanValue(), Actors.CONSOLE)));
    }

    private static Map<String, ConchCatalog.Entry> catalogByName(CrabRuntime runtime) {
        LinkedHashMap<String, ConchCatalog.Entry> rows = new LinkedHashMap<>();
        for (ConchCatalog.Entry entry : runtime.catalog().conches()) {
            if (entry.conch() != null) {
                rows.put(entry.conch(), entry);
            }
        }
        return rows;
    }

    private static void putUpdateFields(ConchCatalog.Entry entry, Map<String, Object> row) {
        if (entry == null) {
            row.put("updateAvailable", false);
            row.put("latestVersion", null);
            row.put("newRequiredCredentials", List.of());
            return;
        }
        row.put("updateAvailable", entry.updateAvailable());
        row.put("latestVersion", entry.latestVersion());
        row.put("newRequiredCredentials", entry.newRequiredCredentials());
    }

    private static void catalog(CrabRuntime runtime, Context ctx) {
        ConchCatalog catalog = runtime.catalog();
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (ConchCatalog.Entry entry : catalog.conches()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("conch", entry.conch());
            row.put("version", entry.version());
            row.put("title", entry.title());
            row.put("description", entry.description());
            row.put("capabilityCount", entry.capabilityCount());
            row.put("subscribed", entry.subscribed());
            row.put("installed", entry.installed());
            row.put("installedVersion", entry.installedVersion());
            row.put("disabled", entry.disabled());
            row.put("localConflict", entry.localConflict());
            row.put("updateAvailable", entry.updateAvailable());
            row.put("latestVersion", entry.latestVersion());
            row.put("newRequiredCredentials", entry.newRequiredCredentials());
            row.put("repairRequired", entry.repairRequired());
            row.put("repairReason", entry.repairReason());
            row.put("message", entry.message());
            row.put("ruleDisabled", entry.ruleDisabled());
            if (entry.ruleDisabled()) {
                putRuleHint(runtime, entry.conch(), row);
            }
            rows.add(row);
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conches", rows);
        body.put("syncedAt", catalog.syncedAt());
        body.put("configured", catalog.configured());
        GatewayResponses.writeData(ctx, body);
    }

    private static void subscribe(CrabRuntime runtime, Context ctx) {
        String conch = ctx.pathParam("conch");
        try {
            ConchOutcome outcome = runtime.subscribe(conch, Actors.CONSOLE);
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("conch", conch);
            body.put("subscribed", subscriptionHeld(runtime, conch, outcome));
            body.put("outcome", outcomeView(outcome));
            GatewayResponses.writeData(ctx, body);
        } catch (SyncException ex) {
            SyncError error = ex.clientError().orElse(null);
            if (error == null) {
                throw ex;
            }
            writeSyncClientError(ctx, error);
        }
    }

    private static void update(CrabRuntime runtime, Context ctx) {
        String conch = ctx.pathParam("conch");
        ConchOutcome outcome = runtime.update(conch, Actors.CONSOLE);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", conch);
        body.put("outcome", outcomeView(outcome));
        GatewayResponses.writeData(ctx, body);
    }

    private static void rollback(CrabRuntime runtime, Context ctx) {
        String conch = ctx.pathParam("conch");
        ConchOutcome outcome = runtime.rollback(conch, Actors.CONSOLE);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", conch);
        body.put("outcome", outcomeView(outcome));
        GatewayResponses.writeData(ctx, body);
    }

    private static void unsubscribe(CrabRuntime runtime, Context ctx) {
        String conch = ctx.pathParam("conch");
        runtime.unsubscribe(conch, Actors.CONSOLE);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", conch);
        body.put("subscribed", false);
        GatewayResponses.writeData(ctx, body);
    }

    /**
     * {@code subscribed: true} only when the row is still there and this sync produced a result
     * for that package. A missing outcome, or {@code NOT_SUBSCRIBED}, is not a successful subscribe.
     */
    private static boolean subscriptionHeld(CrabRuntime runtime, String conch, ConchOutcome outcome) {
        if (outcome == null || outcome.kind() == OutcomeKind.NOT_SUBSCRIBED) {
            return false;
        }
        return runtime.subscribed(conch);
    }

    private static Map<String, Object> outcomeView(ConchOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("conch", outcome.conch());
        row.put("version", outcome.version());
        row.put("kind", outcome.kind() == null ? null : outcome.kind().name());
        row.put("reason", outcome.reason());
        return row;
    }

    private static void sync(CrabRuntime runtime, Context ctx) {
        try {
            Optional<SyncReport> report = runtime.syncOnce(Actors.CONSOLE);
            if (report.isEmpty()) {
                GatewayResponses.writeError(ctx, 409, ErrorCode.NOT_CONFIGURED, SubscriptionException.NOT_CONFIGURED);
                return;
            }
            GatewayResponses.writeData(ctx, syncView(runtime.conchSync().visibleStatus(report.get())));
        } catch (SyncException ex) {
            SyncError error = ex.clientError().orElse(null);
            if (error == null) {
                throw ex;
            }
            writeSyncClientError(ctx, error);
        }
    }

    /**
     * {@link StatusCodes} maps {@link ErrorCode#AUTH_FAILED} to 422 because that is an upstream
     * login failure. A rejected Reef token uses the same code and is HTTP 401.
     */
    private static void writeSyncClientError(Context ctx, SyncError error) {
        int status = error.code() == ErrorCode.AUTH_FAILED ? 401 : StatusCodes.of(error.code());
        GatewayResponses.writeError(ctx, status, error.code(), error.message());
    }

    private static void syncStatus(CrabRuntime runtime, Context ctx) {
        if (runtime.conchSync() == null) {
            GatewayResponses.writeError(ctx, 409, ErrorCode.NOT_CONFIGURED, SubscriptionException.NOT_CONFIGURED);
            return;
        }
        SyncReport report = runtime.lastSync().orElse(null);
        if (report != null) {
            report = runtime.conchSync().visibleStatus(report);
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("report", report == null ? null : syncView(report));
        body.put("latestCrab", report == null ? null : crabView(report.latestCrab().orElse(null)));
        GatewayResponses.writeData(ctx, body);
    }

    private static void reef(CrabRuntime runtime, Context ctx) {
        GatewayResponses.writeData(ctx, runtime.reefStatus());
    }

    private static void importReef(CrabRuntime runtime, Context ctx) throws Exception {
        GatewayResponses.writeData(ctx, runtime.importBootstrap(RequestBodies.read(ctx.req()), Actors.CONSOLE));
    }

    private static void reportSettings(CrabRuntime runtime, Context ctx) {
        GatewayResponses.writeData(ctx, runtime.reportSettings());
    }

    private static void updateReportSettings(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = ApiJson.tree(RequestBodies.read(ctx.req()));
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("invalid report settings");
        }
        rejectUnknownFields(body, Set.of("enabled", "intervalSeconds"));
        JsonNode enabled = body.get("enabled");
        JsonNode interval = body.get("intervalSeconds");
        if (enabled == null || !enabled.isBoolean()) {
            throw new IllegalArgumentException("enabled is required");
        }
        if (interval == null || !interval.isIntegralNumber() || !interval.canConvertToInt()) {
            throw new IllegalArgumentException("intervalSeconds is required");
        }
        GatewayResponses.writeData(ctx, runtime.updateReportSettings(
                enabled.booleanValue(), interval.intValue(), Actors.CONSOLE));
    }

    private static void reportPreview(CrabRuntime runtime, Context ctx) {
        if (!runtime.reportsConfigured()) {
            GatewayResponses.writeError(ctx, 409, ErrorCode.NOT_CONFIGURED, REPORTS_NOT_CONFIGURED);
            return;
        }
        GatewayResponses.writeData(ctx, runtime.reportSender().preview());
    }

    private static void reportSend(CrabRuntime runtime, Context ctx) {
        if (!runtime.reportsConfigured()) {
            GatewayResponses.writeError(ctx, 409, ErrorCode.NOT_CONFIGURED, REPORTS_NOT_CONFIGURED);
            return;
        }
        FlushResult result = runtime.reportSender().confirmAndSend();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("attempted", result.attempted());
        body.put("accepted", result.accepted());
        body.put("status", result.status());
        GatewayResponses.writeData(ctx, body);
    }

    private static Map<String, Object> syncView(SyncReport report) {
        ArrayList<Map<String, Object>> conches = new ArrayList<>();
        for (ConchOutcome outcome : report.conches()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("conch", outcome.conch());
            row.put("version", outcome.version());
            row.put("kind", outcome.kind() == null ? null : outcome.kind().name());
            row.put("reason", outcome.reason());
            conches.add(row);
        }
        ArrayList<Map<String, Object>> changes = new ArrayList<>();
        for (CredentialDeclarationChange change : report.credentialChanges()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("conch", change.conch());
            row.put("addedRequired", change.addedRequired());
            row.put("removed", change.removed());
            changes.add(row);
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("notModified", report.notModified());
        body.put("conches", conches);
        body.put("removed", report.removed());
        body.put("credentialChanges", changes);
        body.put("latestCrab", crabView(report.latestCrab().orElse(null)));
        body.put("error", errorView(report.error().orElse(null)));
        return body;
    }

    private static Map<String, Object> errorView(SyncError error) {
        if (error == null) {
            return null;
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code().name());
        body.put("message", error.message());
        return body;
    }

    private static Map<String, Object> crabView(CrabRelease release) {
        if (release == null) {
            return null;
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("latestVersion", release.latestVersion());
        body.put("downloadUrl", release.downloadUrl());
        return body;
    }

    private static void get(io.javalin.Javalin app, String path, io.javalin.http.Handler handler) {
        handle(app, HandlerType.GET, path, handler);
    }

    private static void post(io.javalin.Javalin app, String path, io.javalin.http.Handler handler) {
        handle(app, HandlerType.POST, path, handler);
    }

    private static void put(io.javalin.Javalin app, String path, io.javalin.http.Handler handler) {
        handle(app, HandlerType.PUT, path, handler);
    }

    private static void delete(io.javalin.Javalin app, String path, io.javalin.http.Handler handler) {
        handle(app, HandlerType.DELETE, path, handler);
    }

    private static void handle(io.javalin.Javalin app, HandlerType type, String path, io.javalin.http.Handler handler) {
        app.addHttpHandler(type, path, ctx -> {
            try {
                handler.handle(ctx);
            } catch (RequestBodies.BodyTooLargeException ex) {
                GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, "request body too large");
            } catch (TrialRejected ex) {
                GatewayResponses.writeError(ctx, ex.status(), ex.code(), ex.getMessage());
            } catch (AccountNotFoundException ex) {
                GatewayResponses.writeError(ctx, 404, ErrorCode.ACCOUNT_NOT_FOUND, "account not found");
            } catch (LocalConchException ex) {
                if (ex.replaceConch() != null) {
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("conch", ex.replaceConch());
                    data.put("confirm", true);
                    GatewayResponses.writeRequestError(ctx, ex.status(), ex.code(), ex.getMessage(), data);
                    return;
                }
                if (ex.hasIssues()) {
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("errors", issues(ex.errors()));
                    data.put("warnings", issues(ex.warnings()));
                    GatewayResponses.writeRequestError(ctx, ex.status(), ex.code(), ex.getMessage(), data);
                    return;
                }
                GatewayResponses.writeError(ctx, ex.status(), ex.code(), ex.getMessage());
            } catch (ReefBootstrapException ex) {
                GatewayResponses.writeError(ctx, ex.status(), ex.code(), ex.getMessage());
            } catch (SubscriptionException ex) {
                GatewayResponses.writeError(ctx, ex.status(), ex.code(), clientMessage(ex));
            } catch (UpdateException ex) {
                GatewayResponses.writeError(ctx, ex.status(), ex.code(), clientMessage(ex));
            } catch (RequestOnlyException ex) {
                GatewayResponses.writeRequestError(ctx, ex.status(), ex.code(), ex.getMessage());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                String message = ex.getMessage();
                if ("capability not found".equals(message)
                        || "api key not found".equals(message)
                        || "conch not found".equals(message)) {
                    GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, message);
                    return;
                }
                GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, clientMessage(ex));
            }
        });
    }

    private static void importLocal(CrabRuntime runtime, Context ctx) throws Exception {
        LocalConches.ImportResult result = runtime.localConches().importBytes(
                localBody(ctx), Actors.CONSOLE, confirm(ctx));
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", result.conch());
        body.put("version", result.version());
        body.put("replaced", result.replaced());
        body.put("warnings", issues(result.warnings()));
        GatewayResponses.writeData(ctx, body);
    }

    private static void listLocal(CrabRuntime runtime, Context ctx) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (LocalConches.LocalPackage pkg : runtime.localConches().packages()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("conch", pkg.conch());
            row.put("file", pkg.file());
            row.put("version", pkg.version());
            row.put("loaded", pkg.loaded());
            row.put("broken", pkg.broken());
            row.put("reason", pkg.reason());
            row.put("ruleDisabled", pkg.ruleDisabled());
            if (pkg.ruleDisabled()) {
                row.put("fixHint", RuleDisabled.fixHint(true, false));
            }
            rows.add(row);
        }
        GatewayResponses.writeData(ctx, Map.of("conches", rows));
    }

    /** Absent or blank is not confirmed. Only {@code true} and {@code false} are accepted. */
    private static boolean confirm(Context ctx) {
        String raw = ctx.queryParam("confirm");
        if (raw == null || raw.isBlank() || "false".equalsIgnoreCase(raw)) {
            return false;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        throw new IllegalArgumentException("confirm 只能是 true 或 false。");
    }

    private static void reloadLocal(CrabRuntime runtime, Context ctx) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (LocalConches.FileResult result : runtime.localConches().reload(Actors.CONSOLE)) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("file", result.file());
            row.put("conch", result.conch());
            row.put("version", result.version());
            row.put("ok", result.ok());
            row.put("issues", issues(result.issues()));
            rows.add(row);
        }
        GatewayResponses.writeData(ctx, Map.of("results", rows));
    }

    private static void deleteLocal(CrabRuntime runtime, Context ctx) {
        String conch = ctx.pathParam("conch");
        runtime.localConches().delete(conch, Actors.CONSOLE);
        GatewayResponses.writeData(ctx, Map.of("conch", conch, "deleted", true));
    }

    private static void exportLocal(CrabRuntime runtime, Context ctx) {
        LocalConches.Export export = runtime.localConches().export(ctx.pathParam("conch"));
        ctx.header("Content-Disposition", "attachment; filename=\"" + export.conch() + "-" + export.version() + ".yaml\"");
        GatewayResponses.writeBytes(ctx, 200, "application/yaml", "no-store", export.bytes());
    }

    private static byte[] localBody(Context ctx) throws Exception {
        String type = ctx.contentType();
        if (type != null && isYaml(type)) {
            return LocalConches.readLimited(ctx.req().getInputStream());
        }
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            UploadedFile file = ctx.uploadedFile("file");
            if (file != null) {
                try (InputStream in = file.content()) {
                    return LocalConches.readLimited(in);
                }
            }
        }
        throw new IllegalArgumentException("file or yaml body is required");
    }

    private static boolean isYaml(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/yaml")
                || lower.startsWith("application/yaml")
                || lower.startsWith("application/x-yaml");
    }

    private static String source(CrabRuntime runtime, String conch) {
        return runtime.registry().origin(conch) == Origin.LOCAL ? "LOCAL" : "REEF";
    }

    /**
     * Reef dropped this official conch from the manifest. The installed copy stays in the list.
     * {@code reefDisabledAt} is the first time this Crab recorded the tombstone.
     */
    private static void putReefDisabled(CrabRuntime runtime, String conch, Map<String, Object> row) {
        boolean reefDisabled = runtime.registry().origin(conch) != Origin.LOCAL
                && runtime.disabledConches().contains(conch);
        row.put("reefDisabled", reefDisabled);
        row.put("reefDisabledAt", reefDisabled ? runtime.disabledConches().disabledAt(conch) : null);
    }

    private static List<Map<String, Object>> issues(List<ValidationIssue> found) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        if (found == null) {
            return rows;
        }
        for (ValidationIssue issue : found) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("code", issue.code());
            row.put("path", issue.path());
            row.put("message", issue.message());
            if (issue.line() != null) {
                row.put("line", issue.line());
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Loaded package, or a verified file the current validator will not load.
     * Account and config calls keep working while the package is disabled. The gateway still refuses calls.
     */
    private static ConchDoc requireConch(CrabRuntime runtime, Context ctx) {
        String name = ctx.pathParam("conch");
        ConchDoc doc = runtime.registry().find(name).orElse(null);
        if (doc != null) {
            return doc;
        }
        ConchSync sync = runtime.conchSync();
        if (sync != null) {
            doc = sync.readableInstalled(name).orElse(null);
            if (doc != null) {
                return doc;
            }
        }
        if (runtime.localConches() != null) {
            doc = runtime.localConches().readableDoc(name).orElse(null);
            if (doc != null) {
                return doc;
            }
        }
        throw new IllegalArgumentException("conch not found");
    }

    private static boolean loginPaused(CrabRuntime runtime, ConchDoc doc, List<Account> accounts) {
        for (Account account : accounts) {
            if (runtime.admin().loginPaused(doc.conch(), account.account())) {
                return true;
            }
        }
        return false;
    }

    /**
     * List and detail share this package flag. It is true when the conch has no account, required
     * config or credentials are missing, stored credentials cannot be decrypted, or any account has
     * automatic login paused. An existing account may leave {@code required: false} credentials unset.
     * Account rows do not use this method.
     */
    private static boolean needsConfiguration(CrabRuntime runtime, ConchDoc doc, List<Account> accounts, boolean loginPaused) {
        if (accounts.isEmpty() || loginPaused) {
            return true;
        }
        if (!DeclaredSettings.missingConfig(doc, runtime.admin().storedConfig(doc.conch())).isEmpty()) {
            return true;
        }
        for (Account account : accounts) {
            if (!runtime.admin().missingCredentials(doc, account.account()).isEmpty()
                    || runtime.admin().credentialsUnreadable(doc.conch(), account.account())) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> credentialDecls(ConchDoc doc) {
        if (doc.credentials() == null) {
            return List.of();
        }
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, CredentialField> entry : doc.credentials().entrySet()) {
            CredentialField field = entry.getValue();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("type", text(field.type()));
            row.put("label", text(field.label()));
            row.put("required", field.required());
            row.put("description", text(field.description()));
            row.put("options", field.options());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> configDecls(ConchDoc doc) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, ConfigField> entry : doc.config().entrySet()) {
            ConfigField field = entry.getValue();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("type", text(field.type()));
            row.put("label", text(field.label()));
            row.put("required", field.required());
            row.put("description", text(field.description()));
            row.put("options", field.options());
            row.put("defaultValue", field.defaultValue());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> summary(ApiKeySummary key) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", key.id());
        row.put("name", key.name());
        row.put("prefix", key.prefix());
        row.put("scope", scope(key.scope()));
        row.put("createdAt", key.createdAt());
        row.put("revokedAt", key.revokedAt());
        row.put("lastUsedAt", key.lastUsedAt());
        return row;
    }

    private static Map<String, Object> scope(ApiKeyScope scope) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("all", scope.grantsAllOfficial());
        row.put("conches", scope.conches());
        row.put("actions", scope.actions());
        row.put("readOnly", scope.readOnlyOnly());
        return row;
    }

    private static Map<String, Object> summary(CallLogStore.CallSummary row) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", row.traceId());
        body.put("at", row.at());
        body.put("origin", row.origin());
        body.put("conch", row.conch());
        body.put("action", row.action());
        body.put("account", row.account());
        body.put("conchVersion", row.conchVersion());
        body.put("success", row.success());
        body.put("errorCode", row.errorCode());
        body.put("errorMessage", row.errorMessage());
        body.put("durationMs", row.durationMs());
        body.put("apiKeyId", row.apiKeyId());
        body.put("source", row.source());
        body.put("channel", row.channel());
        return body;
    }

    private static String driver(ConchDoc doc) {
        String value = doc.driver();
        return value == null || value.isBlank() ? "http" : value;
    }

    private static Map<String, Object> debug(DebugStateStore.DebugSnapshot snapshot) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", snapshot.enabled());
        body.put("expiresAt", snapshot.expiresAt());
        return body;
    }

    private static JsonNode object(Context ctx) throws Exception {
        return ApiJson.objectNode(RequestBodies.read(ctx.req()));
    }

    private static Map<String, String> stringMap(JsonNode node) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return values;
    }

    private static String textual(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.textValue();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.textValue();
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static Set<String> stringSet(JsonNode node) {
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("expected a list of strings");
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("expected a list of strings");
            }
            values.add(item.textValue());
        }
        return Set.copyOf(values);
    }

    private static int pageNumber(Context ctx) {
        return positive(ctx.queryParam("page"), 1, AuditLog.PAGE_OUT_OF_RANGE);
    }

    private static int auditLimit(Context ctx) {
        String raw = ctx.queryParam("limit");
        if (raw == null || raw.isBlank()) {
            return AuditLog.DEFAULT_LIMIT;
        }
        int limit = positive(raw, -1, AuditLog.LIMIT_OUT_OF_RANGE);
        if (limit < 1 || limit > AuditLog.MAX_LIMIT) {
            throw new IllegalArgumentException(AuditLog.LIMIT_OUT_OF_RANGE);
        }
        return limit;
    }

    private static int positive(String raw, int fallback, String message) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(message);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(message);
        }
        return parsed;
    }

    private static void rejectUnknownFields(JsonNode body, Set<String> allowed) {
        ArrayList<String> unknown = new ArrayList<>();
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (unknown.isEmpty()) {
            return;
        }
        unknown.sort(String::compareTo);
        String joined = String.join(", ", unknown);
        if (joined.length() > 120) {
            throw new IllegalArgumentException("unknown field");
        }
        throw new IllegalArgumentException("unknown field: " + joined);
    }

    private static int limit(Context ctx) {
        String raw = ctx.queryParam("limit");
        if (raw == null || raw.isBlank()) {
            return CallLogStore.DEFAULT_LIST_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(AuditLog.LIMIT_OUT_OF_RANGE);
        }
        if (limit < 1 || limit > CallLogStore.LIST_LIMIT) {
            throw new IllegalArgumentException(AuditLog.LIMIT_OUT_OF_RANGE);
        }
        return limit;
    }

    private static String clientMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.length() > 200 || message.indexOf('\n') >= 0) {
            return "invalid input";
        }
        return message;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
