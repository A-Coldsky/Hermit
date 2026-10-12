package dev.hermit.crab.store;

public enum AuditKind {
    ACCOUNT_CREATED,
    ACCOUNT_DELETED,
    DEFAULT_CHANGED,
    CREDENTIALS_WRITTEN,
    CONFIG_PUT,
    CONFIG_DELETED,
    API_KEY_CREATED,
    API_KEY_REVOKED
}
