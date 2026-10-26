package dev.hermit.common.parse;

public final class ConchParseException extends RuntimeException {

    private final String path;

    public ConchParseException(String path, String message) {
        super(prefix(path, message));
        this.path = path == null ? "" : path;
    }

    public String path() {
        return path;
    }

    private static String prefix(String path, String message) {
        if (path == null || path.isEmpty()) {
            return message;
        }
        return path + ": " + message;
    }
}
