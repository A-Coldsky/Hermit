package dev.hermit.common.validate;

import java.util.Set;

import dev.hermit.common.version.SemVer;

/**
 * 文件入参（{@code type: file}）、request 的 {@code multipart} 和 {@code download} 从 1.8.0 起可以保存。
 * 文件本身放在 Crab 的暂存区，规则和调用方之间只传 fileId。
 */
public final class FileTransfers {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.8.0");
    public static final String PARAM_TYPE = dev.hermit.common.model.ParamSpec.FILE_TYPE;
    public static final Set<String> PART_KEYS = Set.of("file", "filename", "content_type");
    public static final Set<String> DOWNLOAD_KEYS = Set.of("filename");

    public static final String FILE_PARAM_VERSION = "使用 type: file 的入参时 min_crab_version 必须不低于 1.8.0";
    public static final String MULTIPART_VERSION = "使用 multipart 时 min_crab_version 必须不低于 1.8.0";
    public static final String DOWNLOAD_VERSION = "使用 download 时 min_crab_version 必须不低于 1.8.0";
    public static final String EXCLUSIVE = "multipart 与 body、form、json 互斥";
    public static final String MULTIPART_TYPE = "multipart 必须是「字段名: 值」的映射";
    public static final String PART_TYPE =
            "multipart 的字段要么是文本，要么是带 file 的映射（可选 filename、content_type）";
    public static final String DOWNLOAD_TYPE = "download 只能是 true、false，或只带 filename 的映射";
    public static final String AUTH_STEPS = "登录步骤和 alive_check 不支持 multipart 和 download，只能写在能力的步骤里";
    public static final String MCP_DRIVER = "driver: mcp 的规则包不支持 type: file、multipart 和 download";

    /** 追加在文件入参的说明后面，Agent 从工具参数说明里读到。 */
    public static final String PARAM_HINT = dev.hermit.common.model.ParamSpec.FILE_HINT;

    private FileTransfers() {
    }
}
