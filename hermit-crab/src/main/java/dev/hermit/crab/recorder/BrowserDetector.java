package dev.hermit.crab.recorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hermit.common.error.ErrorCode;

/**
 * Fast, non-recursive cross-platform Chromium browser detector (Chrome, Edge, Chromium)
 * and default-profile {@code DevToolsActivePort} locator (for Chrome 144+ live remote debugging).
 */
public final class BrowserDetector {
    public static final String ENV_CHROME_PATH = "HERMIT_CHROME_PATH";
    public static final String ENV_BROWSER_PATH = "HERMIT_BROWSER_PATH";
    private static final Pattern WIN_ENV_TOKEN = Pattern.compile("%([^%]+)%");

    public record DetectedBrowser(String id, String name, String path) {
    }

    private BrowserDetector() {
    }

    public static List<DetectedBrowser> detect() {
        return detect(System.getProperty("os.name", ""), System::getenv, System.getProperty("user.home", ""));
    }

    static List<DetectedBrowser> detect(String osName, Function<String, String> env, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, DetectedBrowser> found = new LinkedHashMap<>();

        Optional<Path> configured = configuredOverride(env);
        configured.ifPresent(path -> addIfExecutable(found, "configured", "已配置的浏览器", path));

        if (os.contains("win")) {
            detectWindows(found, env);
        } else if (os.contains("mac")) {
            detectMac(found, userHome);
        } else {
            detectLinux(found, env);
        }
        return List.copyOf(found.values());
    }

    public static Optional<Path> configuredOverride(Function<String, String> env) {
        if (env == null) {
            return Optional.empty();
        }
        for (String key : List.of(ENV_CHROME_PATH, ENV_BROWSER_PATH)) {
            String value = stripQuotes(env.apply(key));
            if (!value.isEmpty()) {
                try {
                    Path candidate = Path.of(value);
                    if (isExecutableFile(candidate)) {
                        return Optional.of(candidate.toAbsolutePath().normalize());
                    }
                } catch (RuntimeException ignored) {
                    // Ignore malformed env path so recorder status stays available.
                }
            }
        }
        return Optional.empty();
    }

    public static Path validateExecutable(String rawPath) {
        String cleaned = stripQuotes(rawPath);
        if (cleaned.isEmpty()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "浏览器路径不能为空。");
        }
        if (cleaned.indexOf('\0') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\r') >= 0) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "浏览器路径格式无效。");
        }
        if (cleaned.toLowerCase(Locale.ROOT).endsWith(".lnk")) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT,
                    "检测到 Windows 快捷方式（.lnk），请填写浏览器真实可执行文件路径（如 chrome.exe 或 msedge.exe，可在快捷方式「属性 → 目标」中复制）。");
        }
        Path path;
        try {
            path = Path.of(cleaned).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "浏览器路径格式无效。");
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "找不到指定的浏览器可执行文件：" + path);
        }
        if (!Files.isExecutable(path)) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "指定的文件不可执行：" + path);
        }
        return path;
    }

    /**
     * Locates the most recently updated {@code DevToolsActivePort} inside standard Chrome / Edge
     * user data directories (created when Chrome 144+ enables {@code chrome://inspect/#remote-debugging}).
     */
    public static Optional<Path> findDefaultDevToolsActivePort() {
        return findDefaultDevToolsActivePort(
                System.getProperty("os.name", ""),
                System::getenv,
                System.getProperty("user.home", ""));
    }

    public static List<Path> findDefaultDevToolsActivePorts() {
        return findDefaultDevToolsActivePorts(
                System.getProperty("os.name", ""),
                System::getenv,
                System.getProperty("user.home", ""));
    }

    static Optional<Path> findDefaultDevToolsActivePort(
            String osName,
            Function<String, String> env,
            String userHome
    ) {
        List<Path> ports = findDefaultDevToolsActivePorts(osName, env, userHome);
        return ports.isEmpty() ? Optional.empty() : Optional.of(ports.get(0));
    }

    static List<Path> findDefaultDevToolsActivePorts(
            String osName,
            Function<String, String> env,
            String userHome
    ) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        ArrayList<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String localAppData = safeEnvAny(env, "LOCALAPPDATA", "LocalAppData");
            if (!localAppData.isEmpty()) {
                try {
                    candidates.add(Path.of(localAppData, "Google", "Chrome", "User Data", "DevToolsActivePort"));
                    candidates.add(Path.of(localAppData, "Microsoft", "Edge", "User Data", "DevToolsActivePort"));
                } catch (RuntimeException ignored) {
                    // Ignore invalid LOCALAPPDATA path.
                }
            }
        } else if (os.contains("mac")) {
            if (userHome != null && !userHome.isBlank()) {
                candidates.add(Path.of(userHome, "Library", "Application Support", "Google", "Chrome", "DevToolsActivePort"));
                candidates.add(Path.of(userHome, "Library", "Application Support", "Microsoft Edge", "DevToolsActivePort"));
                candidates.add(Path.of(userHome, "Library", "Application Support", "Chromium", "DevToolsActivePort"));
            }
        } else {
            if (userHome != null && !userHome.isBlank()) {
                candidates.add(Path.of(userHome, ".config", "google-chrome", "DevToolsActivePort"));
                candidates.add(Path.of(userHome, ".config", "chromium", "DevToolsActivePort"));
                candidates.add(Path.of(userHome, ".config", "microsoft-edge", "DevToolsActivePort"));
            }
        }
        ArrayList<Path> existing = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                existing.add(candidate);
            }
        }
        // Prefer the most recently modified DevToolsActivePort so an active Edge/Chrome wins over a stale file.
        existing.sort(Comparator.comparing(BrowserDetector::lastModifiedOrMin).reversed());
        return List.copyOf(existing);
    }

    private static FileTime lastModifiedOrMin(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ex) {
            return FileTime.fromMillis(0L);
        }
    }

    private static void detectWindows(LinkedHashMap<String, DetectedBrowser> found, Function<String, String> env) {
        List<String> roots = new ArrayList<>();
        addNonBlank(roots, safeEnvAny(env, "ProgramFiles", "PROGRAMFILES"));
        addNonBlank(roots, safeEnvAny(env, "ProgramFiles(x86)", "PROGRAMFILES(X86)"));
        addNonBlank(roots, safeEnvAny(env, "LOCALAPPDATA", "LocalAppData"));

        for (String root : roots) {
            tryAddIfExecutable(found, "chrome", "Google Chrome", root, "Google", "Chrome", "Application", "chrome.exe");
        }
        if (!hasBrowserId(found, "chrome")) {
            findOnPath(env, "chrome.exe", true).ifPresent(p -> addIfExecutable(found, "chrome", "Google Chrome", p));
        }
        if (!hasBrowserId(found, "chrome")) {
            queryWindowsAppPath("chrome.exe", env).ifPresent(p -> addIfExecutable(found, "chrome", "Google Chrome", p));
        }

        for (String root : roots) {
            tryAddIfExecutable(found, "edge", "Microsoft Edge", root, "Microsoft", "Edge", "Application", "msedge.exe");
        }
        if (!hasBrowserId(found, "edge")) {
            findOnPath(env, "msedge.exe", true).ifPresent(p -> addIfExecutable(found, "edge", "Microsoft Edge", p));
        }
        if (!hasBrowserId(found, "edge")) {
            queryWindowsAppPath("msedge.exe", env).ifPresent(p -> addIfExecutable(found, "edge", "Microsoft Edge", p));
        }
    }

    private static void detectMac(LinkedHashMap<String, DetectedBrowser> found, String userHome) {
        addIfExecutable(found, "chrome", "Google Chrome",
                Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        if (userHome != null && !userHome.isBlank()) {
            addIfExecutable(found, "chrome_user", "Google Chrome (用户目录)",
                    Path.of(userHome, "Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        }
        addIfExecutable(found, "edge", "Microsoft Edge",
                Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
        addIfExecutable(found, "chromium", "Chromium",
                Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        addIfExecutable(found, "brave", "Brave Browser",
                Path.of("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"));
    }

    private static void detectLinux(LinkedHashMap<String, DetectedBrowser> found, Function<String, String> env) {
        String[][] specs = {
                {"chrome", "Google Chrome", "google-chrome"},
                {"chrome_stable", "Google Chrome", "google-chrome-stable"},
                {"chromium", "Chromium", "chromium"},
                {"chromium_browser", "Chromium", "chromium-browser"},
                {"edge", "Microsoft Edge", "microsoft-edge"},
                {"edge_stable", "Microsoft Edge", "microsoft-edge-stable"}
        };
        for (String[] spec : specs) {
            String bin = spec[2];
            findOnPath(env, bin, false).ifPresent(p -> addIfExecutable(found, spec[0], spec[1], p));
            addIfExecutable(found, spec[0], spec[1], Path.of("/usr/bin", bin));
            addIfExecutable(found, spec[0], spec[1], Path.of("/usr/local/bin", bin));
            addIfExecutable(found, spec[0], spec[1], Path.of("/snap/bin", bin));
        }
    }

    private static Optional<Path> findOnPath(Function<String, String> env, String binary, boolean windows) {
        String pathEnv = safeEnvAny(env, "PATH", "Path");
        if (pathEnv.isEmpty()) {
            return Optional.empty();
        }
        String sep = windows ? ";" : System.getProperty("path.separator", ":");
        for (String rawDir : pathEnv.split(Pattern.quote(sep))) {
            String dir = stripQuotes(rawDir);
            if (dir.isEmpty()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir, binary);
                if (isExecutableFile(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException ignored) {
                // Skip invalid PATH segment.
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> queryWindowsAppPath(String exeName, Function<String, String> env) {
        for (String hive : List.of("HKLM", "HKCU")) {
            String key = hive + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exeName;
            Process process = null;
            try {
                process = new ProcessBuilder("reg", "query", key, "/ve")
                        .redirectErrorStream(true)
                        .start();
                boolean done = process.waitFor(1500, TimeUnit.MILLISECONDS);
                if (!done) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() != 0) {
                    continue;
                }
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), nativeCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                        if (output.length() > 8192) {
                            break;
                        }
                    }
                }
                Optional<String> parsed = parseWindowsRegQueryOutput(output.toString(), env);
                if (parsed.isPresent()) {
                    Path candidate = Path.of(parsed.get());
                    if (isExecutableFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Parses {@code reg query ... /ve} output for both {@code REG_SZ} and {@code REG_EXPAND_SZ},
     * expanding Windows {@code %VAR%} tokens and stripping surrounding quotes.
     */
    static Optional<String> parseWindowsRegQueryOutput(String output, Function<String, String> env) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        for (String line : output.split("\\R")) {
            String value = null;
            boolean expand = false;
            int expandIdx = line.indexOf("REG_EXPAND_SZ");
            if (expandIdx >= 0) {
                value = line.substring(expandIdx + "REG_EXPAND_SZ".length()).trim();
                expand = true;
            } else {
                int szIdx = line.indexOf("REG_SZ");
                if (szIdx >= 0) {
                    value = line.substring(szIdx + "REG_SZ".length()).trim();
                }
            }
            if (value == null) {
                continue;
            }
            value = stripQuotes(value);
            if (expand && !value.isEmpty()) {
                value = expandWindowsEnvVars(value, env);
            }
            value = stripQuotes(value);
            if (!value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    static String expandWindowsEnvVars(String raw, Function<String, String> env) {
        if (raw == null || env == null || raw.indexOf('%') < 0) {
            return raw == null ? "" : raw;
        }
        Matcher matcher = WIN_ENV_TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String resolved = lookupWindowsEnv(env, varName);
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? resolved : matcher.group(0)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String lookupWindowsEnv(Function<String, String> env, String name) {
        String exact = env.apply(name);
        if (exact != null && !exact.isBlank()) {
            return stripQuotes(exact);
        }
        String upper = env.apply(name.toUpperCase(Locale.ROOT));
        if (upper != null && !upper.isBlank()) {
            return stripQuotes(upper);
        }
        for (String canonical : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA", "USERPROFILE", "SystemDrive")) {
            if (canonical.equalsIgnoreCase(name)) {
                String value = env.apply(canonical);
                if (value != null && !value.isBlank()) {
                    return stripQuotes(value);
                }
            }
        }
        return null;
    }

    static String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static Charset nativeCharset() {
        String prop = System.getProperty("native.encoding");
        if (prop != null && !prop.isBlank()) {
            try {
                return Charset.forName(prop.trim());
            } catch (RuntimeException ignored) {
                // Fall back to JVM default charset.
            }
        }
        return Charset.defaultCharset();
    }

    private static boolean hasBrowserId(LinkedHashMap<String, DetectedBrowser> found, String id) {
        for (DetectedBrowser browser : found.values()) {
            if (id.equals(browser.id())) {
                return true;
            }
        }
        return false;
    }

    private static void tryAddIfExecutable(
            LinkedHashMap<String, DetectedBrowser> found,
            String id,
            String name,
            String first,
            String... more
    ) {
        try {
            addIfExecutable(found, id, name, Path.of(first, more));
        } catch (RuntimeException ignored) {
            // Skip malformed directory root from environment.
        }
    }

    private static void addIfExecutable(
            LinkedHashMap<String, DetectedBrowser> found,
            String id,
            String name,
            Path candidate
    ) {
        if (!isExecutableFile(candidate)) {
            return;
        }
        Path resolved;
        try {
            resolved = candidate.toRealPath();
        } catch (IOException ex) {
            resolved = candidate.toAbsolutePath().normalize();
        }
        String key = resolved.toString().toLowerCase(Locale.ROOT);
        found.putIfAbsent(key, new DetectedBrowser(id, name, candidate.toAbsolutePath().normalize().toString()));
    }

    private static boolean isExecutableFile(Path path) {
        try {
            return path != null && Files.isRegularFile(path) && Files.isExecutable(path);
        } catch (SecurityException ex) {
            return false;
        }
    }

    private static String safeEnvAny(Function<String, String> env, String... keys) {
        if (env == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String val = stripQuotes(env.apply(key));
            if (!val.isEmpty()) {
                return val;
            }
        }
        return "";
    }

    private static void addNonBlank(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }
}
