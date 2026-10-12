package dev.hermit.common.pkg;

public record ShellManifest(
        int format,
        String shell,
        String version,
        String minCrabVersion,
        String sha256
) {
}
