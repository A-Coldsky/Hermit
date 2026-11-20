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
    /** 整个规则包在本机停用。目标是 {@code origin/conch}。 */
    CONCH_SWITCHED_OFF,
    /** 整个规则包在本机重新启用。 */
    CONCH_SWITCHED_ON,
    DEBUG_CHANGED,
    /** Console password accepted or rejected. Detail is {@code {"ok":true|false}} and has no password. */
    CONSOLE_LOGIN,
    TEST_LOGIN,
    SYNC_REQUESTED,
    CREDENTIAL_DECLARATION,
    CONCH_SUBSCRIBED,
    CONCH_UNSUBSCRIBED,
    /** A console or CLI user installed a higher catalog version. Detail has {@code from} and {@code to}. */
    CONCH_UPDATED,
    /**
     * Sync refused to download a previously installed conch that is not loaded.
     * Detail is {@code {"reason":"..."}} with no secrets. One row per reason until the conch
     * loads again or is unsubscribed.
     */
    CONCH_REPAIR_REQUIRED,
    LOCAL_CONCH_IMPORTED,
    LOCAL_CONCH_DELETED,
    /** 覆盖已有本地规则包。详情含新旧版本、摘要、边界、新增地址和备份编号。 */
    LOCAL_CONCH_REPLACED,
    /**
     * 开发者模式下，控制台已经授权的包被自动确认覆盖。
     * 详情在覆盖字段之外还有 {@code autoApproved: true} 和 {@code grantedBy}（授权的控制台 actor）。
     */
    LOCAL_CONCH_AUTO_APPROVED,
    /** 地址扩大、客户端不能当场确认，记一条待确认覆盖。拒绝和到期不记。 */
    LOCAL_CONCH_REPLACE_PENDING,
    /** 用人保存的历史把本地规则包换回去。 */
    LOCAL_CONCH_RESTORED,
    /** Reef first dropped a subscribed official conch from the manifest. */
    REEF_CONCH_DISABLED,
    /** Reef listed that conch again and the disable tombstone was cleared. */
    REEF_CONCH_ENABLED,
    /** A console or CLI import wrote {@code reef-bootstrap.json} and applied it. Detail has no token. */
    REEF_BOOTSTRAPPED,
    /** Console changed report enabled/interval. Detail is the new settings, with no secrets. */
    REPORT_SETTINGS_CHANGED,
    /** A PEM file was written into the CA directory. Detail names the file. */
    TLS_CA_ADDED,
    /** A file was removed from the CA directory. Detail names the file. */
    TLS_CA_REMOVED,
    /** The console reloaded the CA directory and the OS trust store. */
    TLS_RELOADED,
    /** A host:port leaf fingerprint was saved. Detail has the host, port and sha256. */
    TLS_HOST_TRUSTED,
    /** A host:port fingerprint was removed. Detail has the host and port. */
    TLS_HOST_REMOVED
}
