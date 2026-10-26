package dev.hermit.common.pkg;

import java.util.Arrays;
import java.util.Objects;

import dev.hermit.common.model.ConchDoc;

public record VerifiedConch(ConchManifest manifest, byte[] conchYaml, ConchDoc conchDoc) {

    public VerifiedConch {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(conchYaml, "conchYaml");
        Objects.requireNonNull(conchDoc, "conchDoc");
        conchYaml = conchYaml.clone();
    }

    @Override
    public byte[] conchYaml() {
        return conchYaml.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VerifiedConch that)) {
            return false;
        }
        return manifest.equals(that.manifest)
                && Arrays.equals(conchYaml, that.conchYaml)
                && conchDoc.equals(that.conchDoc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(manifest, Arrays.hashCode(conchYaml), conchDoc);
    }
}
