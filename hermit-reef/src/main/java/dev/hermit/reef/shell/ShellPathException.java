package dev.hermit.reef.shell;

/**
 * 路径规范化后不在 {@code data-dir/shells} 之内，或路径段不合法。
 * 消息里不放用户输入，避免把 {@code ..} 一类内容写进日志。
 */
public class ShellPathException extends RuntimeException {

    public ShellPathException() {
        super("shell path is outside the data directory");
    }
}
