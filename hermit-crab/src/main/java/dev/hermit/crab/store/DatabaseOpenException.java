package dev.hermit.crab.store;

/**
 * The local H2 file could not be opened. The message is one line of the root cause
 * (the path error), not the wrapper text and not a stack trace.
 */
public final class DatabaseOpenException extends RuntimeException {
    public DatabaseOpenException(Throwable cause) {
        super(detail(cause), cause);
    }

    /**
     * Deepest cause message that is not the generic database wrapper.
     * Intermediate messages can quote SQL; the path failure sits at the root.
     */
    static String detail(Throwable failure) {
        Throwable chosen = failure;
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (hasText(message) && !isWrapper(message)) {
                chosen = current;
            }
            Throwable next = current.getCause();
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        String message = chosen.getMessage();
        if (!hasText(message)) {
            message = chosen.toString();
        }
        return oneLine(message);
    }

    private static boolean isWrapper(String message) {
        return "database operation failed".equals(message);
    }

    private static boolean hasText(String message) {
        return message != null && !message.isBlank();
    }

    private static String oneLine(String message) {
        String flattened = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (flattened.length() > 2000) {
            return flattened.substring(0, 2000);
        }
        return flattened;
    }
}
