package dev.hermit.common.pkg;

public enum PackageReason {
    UNREADABLE,
    ENTRY_SET,
    DUPLICATE_ENTRY,
    ENTRY_TOO_LARGE,
    BAD_SIGNATURE,
    DIGEST_MISMATCH,
    MANIFEST_INVALID,
    MANIFEST_MISMATCH,
    YAML_INVALID
}
