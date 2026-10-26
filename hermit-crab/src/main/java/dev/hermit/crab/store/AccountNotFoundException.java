package dev.hermit.crab.store;

/**
 * No account matches a resolve request: the named account is missing, or the conch has no default.
 */
public final class AccountNotFoundException extends RuntimeException {
    private final Origin origin;
    private final String conch;
    private final String account;

    public AccountNotFoundException(Origin origin, String conch, String account) {
        super(message(origin, conch, account));
        this.origin = origin;
        this.conch = conch;
        this.account = account;
    }

    public static AccountNotFoundException noDefault(Origin origin, String conch) {
        return new AccountNotFoundException(origin, conch, null);
    }

    public Origin origin() {
        return origin;
    }

    public String conch() {
        return conch;
    }

    /**
     * Named account that was requested, or {@code null} when the conch has no default account.
     */
    public String account() {
        return account;
    }

    private static String message(Origin origin, String conch, String account) {
        if (account == null) {
            return "no default account for " + origin + "/" + conch;
        }
        return "account not found: " + origin + "/" + conch + "/" + account;
    }
}
