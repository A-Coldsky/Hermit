package dev.hermit.crab.store;

/**
 * Where a conch came from. Official and local conches do not share accounts or credentials.
 *
 * <p>Rows and credential AAD both use {@link #name()} ({@code OFFICIAL} or {@code LOCAL}).
 * Changing that spelling makes existing ciphertext undecryptable.
 */
public enum Origin {
    OFFICIAL,
    LOCAL
}
