package dev.hermit.crab.store;

/**
 * No account matches a resolve request: the named account is missing, or the shell has no default.
 */
public final class AccountNotFoundException extends RuntimeException {
    private final Origin origin;
    private final String shell;
    private final String account;

    public AccountNotFoundException(Origin origin, String shell, String account) {
        super(message(origin, shell, account));
        this.origin = origin;
        this.shell = shell;
        this.account = account;
    }

    public static AccountNotFoundException noDefault(Origin origin, String shell) {
        return new AccountNotFoundException(origin, shell, null);
    }

    public Origin origin() {
        return origin;
    }

    public String shell() {
        return shell;
    }

    /**
     * Named account that was requested, or {@code null} when the shell has no default account.
     */
    public String account() {
        return account;
    }

    private static String message(Origin origin, String shell, String account) {
        if (account == null) {
            return "no default account for " + origin + "/" + shell;
        }
        return "account not found: " + origin + "/" + shell + "/" + account;
    }
}
