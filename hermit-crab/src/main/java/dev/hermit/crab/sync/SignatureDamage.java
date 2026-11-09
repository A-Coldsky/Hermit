package dev.hermit.crab.sync;

/**
 * Copy for a subscribed official package that is not loaded because its signature does not verify.
 * Sync does not download it. The console repair action is the existing manual update.
 */
public final class SignatureDamage {
    /** Catalog readers must not treat the package as installed. */
    public static final String MESSAGE = "签名已损坏，当前不可信。";

    /** Detail keeps {@link #MESSAGE} and says the operator can repair it by hand. */
    public static final String DETAIL_MESSAGE = "签名已损坏，当前不可信。可以手动修复。";

    private SignatureDamage() {
    }
}
