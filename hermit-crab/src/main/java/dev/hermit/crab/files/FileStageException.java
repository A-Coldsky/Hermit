package dev.hermit.crab.files;

/** 消息可以直接给调用方看。 */
public final class FileStageException extends RuntimeException {
    public FileStageException(String message) {
        super(message);
    }
}
