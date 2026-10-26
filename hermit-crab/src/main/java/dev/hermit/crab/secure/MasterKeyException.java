package dev.hermit.crab.secure;

/**
 * The master key could not be loaded. The message names the source and never the key bytes.
 */
public final class MasterKeyException extends RuntimeException {
    /**
     * Refused startup when {@code data/master.key} is missing and ciphertext already exists.
     * A new key must not be generated over those rows.
     */
    public static final String MISSING_WITH_CREDENTIALS =
            "主密钥文件缺失，已有凭证无法解密，需重新配置。请恢复 data/master.key 后重启。确认放弃旧凭证时，删除 data 目录中的数据库后再启动，全新安装会自动生成主密钥。";

    public MasterKeyException(String message) {
        super(message);
    }
}
