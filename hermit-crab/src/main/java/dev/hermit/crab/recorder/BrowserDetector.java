package dev.hermit.crab.recorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import dev.hermit.common.error.ErrorCode;

/**
 * Fast, non-recursive cross-platform Chromium browser detector (Chrome, Edge, Chromium)
 * and default-profile {@code DevToolsActivePort} locator (for Chrome 144+ live remote debugging).
 */
public final class BrowserDetector {
    public static final String ENV_CHROME_PATH = "HERMIT_CHROME_PATH";
    public static final String ENV_BROWSER_PATH = "HERMIT_BROWSER_PATH";

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
            String value = env.apply(key);
            if (value != null && !value.isBlank()) {
                Path candidate = Path.of(value.trim());
                if (isExecutableFile(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }

    public static Path validateExecutable(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "浏览器路径不能为空。");
        }
        String trimmed = rawPath.trim();
        if (trimmed.indexOf('\0') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "浏览器路径格式无效。");
        }
        Path path;
        try {
            path = Path.of(trimmed).toAbsolutePath().normalize();
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
     * Locates {@code DevToolsActivePort} inside standard Chrome / Edge user data directories
     * (created when Chrome 144+ enables {@code chrome://inspect/#remote-debugging}).
     */
    public static Optional<Path> findDefaultDevToolsActivePort() {
        return findDefaultDevToolsActivePort(
                System.getProperty("os.name", ""),
                System::getenv,
                System.getProperty("user.home", ""));
    }

    static Optional<Path> findDefaultDevToolsActivePort(
            String osName,
            Function<String, String> env,
            String userHome
    ) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        ArrayList<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String localAppData = safeEnv(env, "LOCALAPPDATA");
            if (!localAppData.isEmpty()) {
                candidates.add(Path.of(localAppData, "Google", "Chrome", "User Data", "DevToolsActivePort"));
                candidates.add(Path.of(localAppData, "Microsoft", "Edge", "User Data", "DevToolsActivePort"));
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
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void detectWindows(LinkedHashMap<String, DetectedBrowser> found, Function<String, String> env) {
        List<String> roots = new ArrayList<>();
        addNonBlank(roots, safeEnv(env, "ProgramFiles"));
        addNonBlank(roots, safeEnv(env, "ProgramFiles(x86)"));
        addNonBlank(roots, safeEnv(env, "LOCALAPPDATA"));

        for (String root : roots) {
            addIfExecutable(found, "chrome", "Google Chrome", Path.of(root, "Google", "Chrome", "Application", "chrome.exe"));
        }
        queryWindowsAppPath("chrome.exe").ifPresent(p -> addIfExecutable(found, "chrome", "Google Chrome", p));
        findOnPath(env, "chrome.exe").ifPresent(p -> addIfExecutable(found, "chrome", "Google Chrome", p));

        for (String root : roots) {
            addIfExecutable(found, "edge", "Microsoft Edge", Path.of(root, "Microsoft", "Edge", "Application", "msedge.exe"));
        }
        queryWindowsAppPath("msedge.exe").ifPresent(p -> addIfExecutable(found, "edge", "Microsoft Edge", p));
        findOnPath(env, "msedge.exe").ifPresent(p -> addIfExecutable(found, "edge", "Microsoft Edge", p));
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
            findOnPath(env, bin).ifPresent(p -> addIfExecutable(found, spec[0], spec[1], p));
            addIfExecutable(found, spec[0], spec[1], Path.of("/usr/bin", bin));
            addIfExecutable(found, spec[0], spec[1], Path.of("/usr/local/bin", bin));
            addIfExecutable(found, spec[0], spec[1], Path.of("/snap/bin", bin));
        }
    }

    private static Optional<Path> findOnPath(Function<String, String> env, String binary) {
        String pathEnv = safeEnv(env, "PATH");
        if (pathEnv.isEmpty()) {
            return Optional.empty();
        }
        String sep = System.getProperty("path.separator", ":");
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(sep))) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir.trim(), binary);
                if (isExecutableFile(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException ignored) {
                // Skip invalid PATH segment.
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> queryWindowsAppPath(String exeName) {
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
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int idx = line.indexOf("REG_SZ");
                        if (idx >= 0) {
                            String value = line.substring(idx + "REG_SZ".length()).trim();
                            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                                value = value.substring(1, value.length() - 1);
                            }
                            if (!value.isBlank()) {
                                Path candidate = Path.of(value);
                                if (isExecutableFile(candidate)) {
                                    return Optional.of(candidate);
                                }
                            }
                        }
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

    private static String safeEnv(Function<String, String> env, String key) {
        if (env == null) {
            return "";
        }
        String val = env.apply(key);
        return val == null ? "" : val.trim();
    }

    private static void addNonBlank(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }
}
