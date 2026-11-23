package dev.hermit.crab.files;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    private final ArrayList<String> attemptFiles = new ArrayList<>();
    private final LinkedHashSet<String> generatedTokens = new LinkedHashSet<>();

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

    public StagedFile commit(FileStage.Spool spool, String name, String mime) {
        return spool.commit(name, mime, owner);
    }

    public Map<String, Object> view(StagedFile file) {
        return file.view(baseUrl);
    }

    /** 开始一次新的动作执行尝试：清理上一轮残留的跟踪列表，准备记录本轮下载落盘的文件。 */
    public void beginAttempt() {
        attemptFiles.clear();
    }

    /** 记录本轮尝试中已提交进暂存区的文件编号，供失败/重放时回滚删除。 */
    public void recordCommitted(String fileId) {
        if (fileId != null && !fileId.isBlank()) {
            attemptFiles.add(fileId);
        }
    }

    /** 记录本次调用生成的下载令牌（不随尝试回滚清空，用于脱敏日志与错误信息）。 */
    public void recordToken(String token) {
        if (token != null && !token.isBlank()) {
            generatedTokens.add(token);
        }
    }

    /** 本次调用中生成的全部暂存区下载令牌。 */
    public Set<String> generatedTokens() {
        return generatedTokens.isEmpty() ? Set.of() : Set.copyOf(generatedTokens);
    }

    /** 动作执行成功，保留本轮已提交的暂存文件。 */
    public void commitAttempt() {
        attemptFiles.clear();
    }

    /** 动作尝试失败或会话失效需要重放：删除本轮尝试已提交的所有暂存文件。 */
    public void rollbackAttempt() {
        if (attemptFiles.isEmpty()) {
            return;
        }
        for (String fileId : attemptFiles) {
            stage.delete(fileId);
        }
        attemptFiles.clear();
    }
}
