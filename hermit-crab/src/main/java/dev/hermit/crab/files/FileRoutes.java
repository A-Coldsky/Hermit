package dev.hermit.crab.files;

/** 暂存区的 HTTP 路径。规则包名以小写字母开头，{@code /api/-/} 不会和能力路径撞上。 */
public final class FileRoutes {
    public static final String UPLOAD = "/api/-/files";
    public static final String DOWNLOAD_PREFIX = "/api/-/files/";

    private FileRoutes() {
    }
}
