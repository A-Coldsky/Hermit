package dev.hermit.crab.store;

public enum AuditKind {
    ACCOUNT_CREATED,
    ACCOUNT_DELETED,
    DEFAULT_CHANGED,
    CREDENTIALS_WRITTEN,
    CONFIG_PUT,
    CONFIG_DELETED,
    API_KEY_CREATED,
    API_KEY_REVOKED,
    CAPABILITY_ENABLED,
    CAPABILITY_DISABLED,
    DEBUG_CHANGED,
    TEST_LOGIN,
    SYNC_REQUESTED,
    CREDENTIAL_DECLARATION,
    CONCH_SUBSCRIBED,
    CONCH_UNSUBSCRIBED,
    /** A console or CLI user installed a higher catalog version. Detail has {@code from} and {@code to}. */
    CONCH_UPDATED,
    LOCAL_CONCH_IMPORTED,
    LOCAL_CONCH_DELETED,
    /** Reef first dropped a subscribed official conch from the manifest. */
    REEF_CONCH_DISABLED,
    /** Reef listed that conch again and the disable tombstone was cleared. */
    REEF_CONCH_ENABLED,
    /** A console or CLI import wrote {@code reef-bootstrap.json} and applied it. Detail has no token. */
    REEF_BOOTSTRAPPED,
    /** Console changed report enabled/interval. Detail is the new settings, with no secrets. */
    REPORT_SETTINGS_CHANGED
}
