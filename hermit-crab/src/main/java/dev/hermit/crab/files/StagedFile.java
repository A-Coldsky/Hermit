package dev.hermit.crab.files;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 暂存区里的一个文件。{@code owner} 为空表示控制台上传，所有 API Key 可用。 */
public record StagedFile(
        String fileId,
        String token,
        String name,
        String mime,
        long size,
        String sha256,
        Instant createdAt,
        Instant expiresAt,
        String owner,
        Path path
) {
    /** 给调用方的描述。{@code baseUrl} 为空时不带下载链接。 */
    public Map<String, Object> view(String baseUrl) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("fileId", fileId);
        if (baseUrl != null) {
            view.put("url", baseUrl + FileRoutes.DOWNLOAD_PREFIX + token);
        }
        view.put("name", name);
        view.put("size", size);
        view.put("mime", mime);
        view.put("expiresAt", expiresAt.toString());
        return view;
    }

    /** 写进追踪和日志的部分：没有下载令牌，也没有内容。 */
    public Map<String, Object> metadata() {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("fileId", fileId);
        view.put("name", name);
        view.put("size", size);
        view.put("mime", mime);
        view.put("sha256", sha256);
        return view;
    }
}
