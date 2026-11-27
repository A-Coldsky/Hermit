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
     * 1.11.0 起可以载入 {@code auth.origins}：登录步骤可以访问声明的认证地址（门户、SSO）。
     * 1.12.0 起可以载入 {@code auth.expired_when}、{@code auth.alive_check.expect}，
     * 以及 {@code assert} 的 {@code on_fail: session_expired} 和变量断言键。
     * 1.13.0 起可以载入授权器（{@code driver: auth}）和引用它的业务包（{@code auth.use}）。
     * 1.14.0 起可以载入 {@code hermit} 加解密与 GBK 编解码辅助函数，以及声明 {@code credential: true}
     * 的 {@code script} 步骤读取 {@code vars.login} 与授权器 {@code vars.auth}。
     */
    public static final SemVer CURRENT = SemVer.parse("1.14.0");

    private CrabVersion() {
    }
}
