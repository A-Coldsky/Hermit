package dev.hermit.crab.files;

import java.util.Map;

/**
 * 当前线程这次调用能用的暂存区和身份。{@link dev.hermit.crab.runtime.Invoker} 在执行前后设置和清除，
 * 引擎发请求时从这里取文件、存文件。测试登录和保活请求没有它，不能传文件。
 */
public final class FileScope {
    private static final ThreadLocal<FileScope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> PUBLIC_BASE = new ThreadLocal<>();

    private final FileStage stage;
    private final String owner;
    private final String baseUrl;

    private FileScope(FileStage stage, String owner, String baseUrl) {
        this.stage = stage;
        this.owner = owner;
        this.baseUrl = baseUrl;
    }

    /** 返回之前的值，交给 {@link #restore}。 */
    public static FileScope begin(FileStage stage, String owner, String fallbackBase) {
        FileScope previous = CURRENT.get();
        if (stage == null) {
            CURRENT.remove();
            return previous;
        }
        String base = PUBLIC_BASE.get();
        CURRENT.set(new FileScope(stage, owner, base == null ? fallbackBase : base));
        return previous;
    }

    public static void restore(FileScope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static FileScope current() {
        return CURRENT.get();
    }

    /** HTTP 层在请求开始时记下对外地址，下载链接用它拼。 */
    public static void publicBase(String base) {
        if (base == null) {
            PUBLIC_BASE.remove();
        } else {
            PUBLIC_BASE.set(base);
        }
    }

    public FileStage stage() {
        return stage;
    }

    public StagedFile require(String fileId) {
        return stage.find(fileId, owner).orElseThrow(() -> new FileStageException(FileStage.MISSING));
    }

    public StagedFile store(byte[] bytes, String name, String mime) {
        return stage.store(bytes, name, mime, owner);
    }

    public Map<String, Object> view(StagedFile file) {
        return file.view(baseUrl);
    }
}
