package dev.hermit.crab.files;

/** 暂存区的三项限制，在 Crab 控制台里改。 */
public record FileStageSettings(int ttlSeconds, long maxFileBytes, long maxTotalBytes) {

    public static final int MIN_TTL_SECONDS = 60;
    public static final int MAX_TTL_SECONDS = 7 * 24 * 3600;
    public static final long MIN_FILE_BYTES = 1024;
    public static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024 * 1024;

    public FileStageSettings {
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("有效期要在 1 分钟到 7 天之间");
        }
        if (maxFileBytes < MIN_FILE_BYTES || maxFileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("单文件上限要在 1 KiB 到 1 GiB 之间");
        }
        if (maxTotalBytes < maxFileBytes || maxTotalBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("总容量不能小于单文件上限，也不能超过 100 GiB");
        }
    }

    /** 有效期 1 小时，单文件 50 MiB，总量 1 GiB。 */
    public static FileStageSettings defaults() {
        return new FileStageSettings(3600, 50L * 1024 * 1024, 1024L * 1024 * 1024);
    }
}
