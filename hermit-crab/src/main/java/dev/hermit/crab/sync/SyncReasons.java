package dev.hermit.crab.sync;

public final class SyncReasons {

    public static final String SAME_VERSION = "SAME_VERSION";
    public static final String MIN_CRAB_VERSION = "MIN_CRAB_VERSION";
    public static final String UPDATE_AVAILABLE = "UPDATE_AVAILABLE";
    public static final String OLDER_MANIFEST = "OLDER_MANIFEST";
    public static final String ZIP_TOO_LARGE = "ZIP_TOO_LARGE";
    public static final String MANIFEST_ENTRY_MISMATCH = "MANIFEST_ENTRY_MISMATCH";
    public static final String VERSION_INVALID = "VERSION_INVALID";
    public static final String CONCH_NAME = "CONCH_NAME";
    public static final String PATH_UNSAFE = "PATH_UNSAFE";
    public static final String CURRENT_WRITE_FAILED = "CURRENT_WRITE_FAILED";
    public static final String REGISTRY_REJECTED = "REGISTRY_REJECTED";
    public static final String CREDENTIAL_DELETE_FAILED = "CREDENTIAL_DELETE_FAILED";
    public static final String PACKAGE_MISSING = "PACKAGE_MISSING";
    public static final String DISABLED = "DISABLED";
    public static final String DISABLE_FAILED = "DISABLE_FAILED";
    /** Manifest entry left untouched because a local conch already uses that name. */
    public static final String LOCAL_KEPT = "LOCAL_KEPT";

    private SyncReasons() {
    }

    public static String http(int status) {
        return "HTTP_" + status;
    }
}
