package dev.hermit.crab.sync;

public final class SyncReasons {

    public static final String SAME_VERSION = "SAME_VERSION";
    public static final String MIN_CRAB_VERSION = "MIN_CRAB_VERSION";
    public static final String PINNED = "PINNED";
    public static final String ZIP_TOO_LARGE = "ZIP_TOO_LARGE";
    public static final String MANIFEST_ENTRY_MISMATCH = "MANIFEST_ENTRY_MISMATCH";
    public static final String VERSION_INVALID = "VERSION_INVALID";
    public static final String PATH_UNSAFE = "PATH_UNSAFE";
    public static final String REGISTRY_REJECTED = "REGISTRY_REJECTED";
    public static final String CREDENTIAL_DELETE_FAILED = "CREDENTIAL_DELETE_FAILED";
    public static final String PACKAGE_MISSING = "PACKAGE_MISSING";

    private SyncReasons() {
    }

    public static String http(int status) {
        return "HTTP_" + status;
    }
}
