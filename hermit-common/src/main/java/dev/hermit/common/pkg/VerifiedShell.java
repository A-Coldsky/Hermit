package dev.hermit.common.pkg;

import java.util.Arrays;
import java.util.Objects;

import dev.hermit.common.model.ShellDoc;

public record VerifiedShell(ShellManifest manifest, byte[] shellYaml, ShellDoc shellDoc) {

    public VerifiedShell {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(shellYaml, "shellYaml");
        Objects.requireNonNull(shellDoc, "shellDoc");
        shellYaml = shellYaml.clone();
    }

    @Override
    public byte[] shellYaml() {
        return shellYaml.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VerifiedShell that)) {
            return false;
        }
        return manifest.equals(that.manifest)
                && Arrays.equals(shellYaml, that.shellYaml)
                && shellDoc.equals(that.shellDoc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(manifest, Arrays.hashCode(shellYaml), shellDoc);
    }
}
