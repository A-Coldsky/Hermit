package dev.hermit.reef.conch;

/**
 * 路径规范化后不在 {@code data-dir/conches} 之内，或路径段不合法。
 * 消息里不放用户输入，避免把 {@code ..} 一类内容写进日志。
 */
public class ConchPathException extends RuntimeException {

    public ConchPathException() {
        super("conch path is outside the data directory");
    }
}
