package dev.hermit.common.pkg;

public record ConchManifest(
        int format,
        String conch,
        String version,
        String minCrabVersion,
        String sha256
) {
}
