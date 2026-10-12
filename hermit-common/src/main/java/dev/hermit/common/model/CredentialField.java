package dev.hermit.common.model;

import java.util.List;

public record CredentialField(
        String type,
        String label,
        boolean required,
        String description,
        List<String> options
) {
    public CredentialField {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
