package dev.hermit.common.template;

public final class TemplateException extends RuntimeException {

    private final String path;

    public TemplateException(String path, String message) {
        this(path, message, null);
    }

    public TemplateException(String path, String message, Throwable cause) {
        super(prefix(path, message), cause);
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
