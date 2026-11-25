package dev.hermit.crab.gateway;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.recorder.BrowserDetector;
import dev.hermit.crab.recorder.CaptureSnapshot;
import dev.hermit.crab.recorder.HarImporter;
import dev.hermit.crab.recorder.RecordedEntry;
import dev.hermit.crab.recorder.RecorderException;
import dev.hermit.crab.recorder.RecorderManager;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

/**
 * Console API routes for developer-mode browser HAR recording, HAR import, and redacted snapshots.
 * Protected by Crab's loopback + {@code X-Hermit-Console: 1} + console session middleware.
 */
final class RecorderConsoleApi {
    private static final Set<String> START_FIELDS = Set.of(
            "mode", "startUrl", "browserPath", "debugEndpoint", "headless", "ignoreCertErrors");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "title", "entryIds", "extraSecrets");

    private RecorderConsoleApi() {
    }

    static void register(Javalin app, CrabRuntime runtime) {
        app.get("/console/api/recorder/status", ctx -> run(ctx, () -> status(runtime, ctx)));
        app.post("/console/api/recorder/start", ctx -> run(ctx, () -> start(runtime, ctx)));
        app.post("/console/api/recorder/stop", ctx -> run(ctx, () -> stop(runtime, ctx)));
        app.get("/console/api/recorder/entries", ctx -> run(ctx, () -> entries(runtime, ctx)));
        app.get("/console/api/recorder/entries/{id}", ctx -> run(ctx, () -> entry(runtime, ctx)));
        app.post("/console/api/recorder/import-har", ctx -> run(ctx, () -> importHar(runtime, ctx)));
        app.delete("/console/api/recorder/session", ctx -> run(ctx, () -> clearSession(runtime, ctx)));
        app.get("/console/api/recorder/snapshots", ctx -> run(ctx, () -> snapshots(runtime, ctx)));
        app.post("/console/api/recorder/snapshots", ctx -> run(ctx, () -> createSnapshot(runtime, ctx)));
        app.get("/console/api/recorder/snapshots/{id}", ctx -> run(ctx, () -> snapshot(runtime, ctx)));
        app.get("/console/api/recorder/snapshots/{id}/har", ctx -> run(ctx, () -> exportHar(runtime, ctx)));
        app.delete("/console/api/recorder/snapshots/{id}", ctx -> run(ctx, () -> deleteSnapshot(runtime, ctx)));
    }

    private static void status(CrabRuntime runtime, Context ctx) {
        GatewayResponses.writeData(ctx, statusView(runtime.recorder().status()));
    }

    private static void start(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = ApiJson.objectNode(RequestBodies.read(ctx.req()));
        rejectUnknown(body, START_FIELDS);
        String mode = optionalText(body, "mode");
        String startUrl = optionalText(body, "startUrl");
        String browserPath = optionalText(body, "browserPath");
        String debugEndpoint = optionalText(body, "debugEndpoint");
        boolean headless = optionalBool(body, "headless", false);
        boolean ignoreCertErrors = optionalBool(body, "ignoreCertErrors", false);
        RecorderManager.RecorderStatus result = runtime.recorder().start(
                new RecorderManager.StartRequest(
                        mode, startUrl, browserPath, debugEndpoint, headless, ignoreCertErrors));
        GatewayResponses.writeData(ctx, statusView(result));
    }

    private static void stop(CrabRuntime runtime, Context ctx) {
        GatewayResponses.writeData(ctx, statusView(runtime.recorder().stop()));
    }

    private static void entries(CrabRuntime runtime, Context ctx) {
        boolean includeStatic = "true".equalsIgnoreCase(ctx.queryParam("includeStatic"))
                || "1".equals(ctx.queryParam("includeStatic"));
        List<RecordedEntry> list = runtime.recorder().listSessionEntries(includeStatic);
        ArrayList<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (RecordedEntry entry : list) {
            rows.add(entrySummaryView(entry));
        }
        GatewayResponses.writeData(ctx, Map.of("entries", rows));
    }

    private static void entry(CrabRuntime runtime, Context ctx) {
        String id = ctx.pathParam("id");
        RecordedEntry found = runtime.recorder().getSessionEntry(id).orElseThrow(() ->
                new RecorderException(404, ErrorCode.NOT_FOUND, "请求记录不存在。"));
        GatewayResponses.writeData(ctx, entryDetailView(found));
    }

    private static void importHar(CrabRuntime runtime, Context ctx) throws Exception {
        String fileName = ctx.queryParam("name");
        byte[] bytes;
        String contentType = ctx.contentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            UploadedFile uploaded = ctx.uploadedFile("file");
            if (uploaded == null) {
                throw new RecorderException(400, ErrorCode.INVALID_INPUT, "请上传 HAR 文件。");
            }
            if (fileName == null || fileName.isBlank()) {
                fileName = uploaded.filename();
            }
            try (InputStream in = uploaded.content()) {
                bytes = readLimitedHar(in);
            }
        } else {
            bytes = readLimitedHar(ctx.req().getInputStream());
        }
        RecorderManager.RecorderStatus result = runtime.recorder().importHar(bytes, fileName);
        GatewayResponses.writeData(ctx, statusView(result));
    }

    private static void clearSession(CrabRuntime runtime, Context ctx) {
        GatewayResponses.writeData(ctx, statusView(runtime.recorder().clearSession()));
    }

    private static void snapshots(CrabRuntime runtime, Context ctx) {
        List<CaptureSnapshot> list = runtime.recorder().listSnapshots();
        ArrayList<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (CaptureSnapshot snapshot : list) {
            rows.add(snapshotSummaryView(snapshot));
        }
        GatewayResponses.writeData(ctx, Map.of("snapshots", rows));
    }

    private static void createSnapshot(CrabRuntime runtime, Context ctx) throws Exception {
        JsonNode body = ApiJson.objectNode(RequestBodies.read(ctx.req()));
        rejectUnknown(body, SNAPSHOT_FIELDS);
        String title = optionalText(body, "title");
        List<String> entryIds = stringList(body.get("entryIds"), true);
        List<String> extraSecrets = stringList(body.get("extraSecrets"), false);
        CaptureSnapshot created = runtime.recorder().createSnapshot(title, entryIds, extraSecrets);
        GatewayResponses.writeData(ctx, snapshotDetailView(created));
    }

    private static void snapshot(CrabRuntime runtime, Context ctx) {
        String id = ctx.pathParam("id");
        CaptureSnapshot found = runtime.recorder().getSnapshot(id).orElseThrow(() ->
                new RecorderException(404, ErrorCode.NOT_FOUND, "抓包快照不存在或已清除。"));
        GatewayResponses.writeData(ctx, snapshotDetailView(found));
    }

    private static void exportHar(CrabRuntime runtime, Context ctx) {
        String id = ctx.pathParam("id");
        byte[] bytes = runtime.recorder().exportHar(id);
        String safeId = id.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeId.isEmpty()) {
            safeId = "capture";
        }
        ctx.header("Content-Disposition", "attachment; filename=\"hermit-" + safeId + ".har\"");
        GatewayResponses.writeBytes(ctx, 200, "application/json; charset=utf-8", "no-store", bytes);
    }

    private static void deleteSnapshot(CrabRuntime runtime, Context ctx) {
        String id = ctx.pathParam("id");
        boolean deleted = runtime.recorder().deleteSnapshot(id);
        if (!deleted) {
            throw new RecorderException(404, ErrorCode.NOT_FOUND, "抓包快照不存在或已清除。");
        }
        GatewayResponses.writeData(ctx, Map.of("id", id, "deleted", true));
    }

    private static Map<String, Object> statusView(RecorderManager.RecorderStatus status) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("devMode", status.devMode());
        map.put("state", status.state().name());
        map.put("sessionId", status.sessionId());
        map.put("mode", status.mode());
        map.put("source", status.source());
        map.put("startUrl", status.startUrl());
        map.put("startedAt", status.startedAt());
        map.put("stoppedAt", status.stoppedAt());
        map.put("totalEntries", status.totalEntries());
        map.put("businessEntries", status.businessEntries());
        map.put("browserConnected", status.browserConnected());
        map.put("disconnectReason", status.disconnectReason());
        map.put("defaultRemoteDebuggingDetected", status.defaultRemoteDebuggingDetected());

        ArrayList<Map<String, Object>> browsers = new ArrayList<>(status.browsers().size());
        for (BrowserDetector.DetectedBrowser b : status.browsers()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.id());
            row.put("name", b.name());
            row.put("path", b.path());
            browsers.add(row);
        }
        map.put("browsers", browsers);

        ArrayList<Map<String, Object>> snaps = new ArrayList<>(status.snapshots().size());
        for (CaptureSnapshot snap : status.snapshots()) {
            snaps.add(snapshotSummaryView(snap));
        }
        map.put("snapshots", snaps);
        return map;
    }

    private static Map<String, Object> snapshotSummaryView(CaptureSnapshot snapshot) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("id", snapshot.id());
        map.put("title", snapshot.title());
        map.put("createdAt", snapshot.createdAt());
        map.put("source", snapshot.source());
        map.put("origins", snapshot.origins());
        map.put("cookieNames", snapshot.cookieNames());
        map.put("hasRedirects", snapshot.hasRedirects());
        map.put("hasDownloads", snapshot.hasDownloads());
        map.put("entryCount", snapshot.entries().size());
        return map;
    }

    private static Map<String, Object> snapshotDetailView(CaptureSnapshot snapshot) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>(snapshotSummaryView(snapshot));
        ArrayList<Map<String, Object>> rows = new ArrayList<>(snapshot.entries().size());
        for (RecordedEntry entry : snapshot.entries()) {
            rows.add(entrySummaryView(entry));
        }
        map.put("entries", rows);
        return map;
    }

    private static Map<String, Object> entrySummaryView(RecordedEntry entry) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id());
        map.put("index", entry.index());
        map.put("startedAt", entry.startedAt());
        map.put("durationMs", entry.durationMs());
        map.put("method", entry.method());
        map.put("url", entry.url());
        map.put("origin", entry.origin());
        map.put("path", entry.path());
        map.put("resourceType", entry.resourceType());
        map.put("staticAsset", entry.staticAsset());
        map.put("status", entry.status());
        map.put("statusText", entry.statusText());
        map.put("mimeType", entry.mimeType());
        map.put("redirectTo", entry.redirectTo());
        map.put("binary", entry.binary());
        map.put("contentDisposition", entry.contentDisposition());
        map.put("responseBytes", entry.responseBytes());
        map.put("errorText", entry.errorText());
        map.put("hasRequestBody", entry.requestBody() != null && !entry.requestBody().isEmpty());
        map.put("hasResponseBody", entry.responseBody() != null && !entry.responseBody().isEmpty());
        return map;
    }

    private static Map<String, Object> entryDetailView(RecordedEntry entry) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>(entrySummaryView(entry));
        map.put("requestHeaders", headerRows(entry.requestHeaders()));
        map.put("requestBody", entry.requestBody());
        map.put("requestBodyTruncated", entry.requestBodyTruncated());
        map.put("responseHeaders", headerRows(entry.responseHeaders()));
        map.put("responseBody", entry.responseBody());
        map.put("responseBodyTruncated", entry.responseBodyTruncated());
        return map;
    }

    private static List<Map<String, Object>> headerRows(List<RecordedEntry.HeaderPair> headers) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>(headers.size());
        for (RecordedEntry.HeaderPair h : headers) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", h.name());
            row.put("value", h.value());
            rows.add(row);
        }
        return rows;
    }

    private static byte[] readLimitedHar(InputStream in) throws Exception {
        byte[] data = in.readNBytes(HarImporter.MAX_HAR_BYTES + 1);
        if (data.length > HarImporter.MAX_HAR_BYTES) {
            throw new RecorderException(413, ErrorCode.INVALID_INPUT, "HAR 文件超过 20 MiB 上限。");
        }
        return data;
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, field + " 必须是字符串。");
        }
        return node.textValue();
    }

    private static boolean optionalBool(JsonNode body, String field, boolean fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, field + " 必须是布尔值。");
        }
        return node.booleanValue();
    }

    private static List<String> stringList(JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new RecorderException(400, ErrorCode.INVALID_INPUT, "entryIds 不能为空。");
            }
            return List.of();
        }
        if (!node.isArray()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "参数必须是字符串数组。");
        }
        ArrayList<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new RecorderException(400, ErrorCode.INVALID_INPUT, "数组项必须是字符串。");
            }
            out.add(item.textValue());
        }
        return out;
    }

    private static void rejectUnknown(JsonNode body, Set<String> allowed) {
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new RecorderException(400, ErrorCode.INVALID_INPUT, "未知字段：" + name);
            }
        });
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static void run(Context ctx, Action action) {
        if (ConsoleApi.apiKeyPresented(ctx)) {
            GatewayResponses.writeError(ctx, 403, ErrorCode.FORBIDDEN, "浏览器录制仅允许在控制台操作，不支持 API Key。");
            return;
        }
        try {
            action.run();
        } catch (RecorderException ex) {
            GatewayResponses.writeError(ctx, ex.status(), ex.code(), ConsoleApi.clientMessage(ex));
        } catch (RequestBodies.BodyTooLargeException ex) {
            GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, "request body too large");
        } catch (IllegalArgumentException ex) {
            GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, ConsoleApi.clientMessage(ex));
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(RecorderConsoleApi.class.getName())
                    .warning("recorder request failed: " + ex.getClass().getName() + ": " + ex.getMessage());
            GatewayResponses.writeError(ctx, 500, ErrorCode.INTERNAL, "浏览器录制操作失败");
        }
    }
}
