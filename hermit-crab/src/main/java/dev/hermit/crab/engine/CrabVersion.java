package dev.hermit.crab.engine;

import dev.hermit.common.version.SemVer;

public final class CrabVersion {

    /**
     * 加载 Conch 时交给校验器的当前版本。1.1.0 起可以载入 {@code driver: mcp}，
     * 1.2.0 起可以载入脚本库，1.3.0 起可载入 {@code login} 与 {@code auth.cookies}，
     * 1.4.0 起可载入登录步骤 {@code ask}。1.5.0 起 query 默认按 URL 编码，
     * 规则可以用 {@code query_style: form} 退回表单编码。1.6.0 起可以载入引号下标。
     * 1.7.0 起可以载入 request 的原始 {@code body} 和 {@code xml_escape}。
     * 1.8.0 起可以载入 {@code type: file} 入参和 request 的 {@code multipart}、{@code download}。
     * 1.9.0 起可以载入步骤 {@code if}、{@code repeat}、request 的 {@code retry} 和 {@code auth.concurrent_reads}。
     * 1.10.0 起可以载入顶层 {@code groups} 与能力 {@code group}（一级与二级目录）。
     */
    public static final SemVer CURRENT = SemVer.parse("1.10.0");

    private CrabVersion() {
    }
}
