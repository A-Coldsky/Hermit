package dev.hermit.common.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code origins} 是登录时除 {@code base_url} 之外还要访问的认证地址（门户、SSO）。
 * 每项是 {@code scheme://host[:port]}，或整项引用一个 {@code type: url} 的配置项。只对登录步骤有效。
 * {@code originsDeclared} 仅用于区分「没写 origins」和「显式写了空/null 的 origins」以便检查版本门槛，
 * 不进规范 JSON，也不参与文档相等性。
 */
public record Auth(
        List<Step> steps,
        AliveCheck aliveCheck,
        Map<String, String> cookies,
        boolean concurrentReads,
        List<String> origins,
        @JsonIgnore boolean originsDeclared
) {
    public Auth {
        steps = steps == null ? List.of() : List.copyOf(steps);
        cookies = cookies == null || cookies.isEmpty() ? Map.of() : Map.copyOf(cookies);
        origins = origins == null || origins.isEmpty() ? List.of() : List.copyOf(origins);
        originsDeclared = originsDeclared || !origins.isEmpty();
    }

    public Auth(List<Step> steps, AliveCheck aliveCheck, Map<String, String> cookies,
            boolean concurrentReads, List<String> origins) {
        this(steps, aliveCheck, cookies, concurrentReads, origins, origins != null && !origins.isEmpty());
    }

    public Auth(List<Step> steps, AliveCheck aliveCheck, Map<String, String> cookies, boolean concurrentReads) {
        this(steps, aliveCheck, cookies, concurrentReads, List.of(), false);
    }

    public Auth(List<Step> steps, AliveCheck aliveCheck, Map<String, String> cookies) {
        this(steps, aliveCheck, cookies, false);
    }

    /** 没打开时不进规范 JSON，旧包的快照令牌不变。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @Override
    public boolean concurrentReads() {
        return concurrentReads;
    }

    public Auth(List<Step> steps, AliveCheck aliveCheck) {
        this(steps, aliveCheck, Map.of());
    }

    /** 空映射不进规范 JSON，避免没写 {@code auth.cookies} 的包改掉快照令牌。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Override
    public Map<String, String> cookies() {
        return cookies;
    }

    /** 没写时不进规范 JSON，旧包的快照令牌不变。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Override
    public List<String> origins() {
        return origins;
    }

    @JsonIgnore
    @Override
    public boolean originsDeclared() {
        return originsDeclared;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Auth auth)) {
            return false;
        }
        return concurrentReads == auth.concurrentReads
                && Objects.equals(steps, auth.steps)
                && Objects.equals(aliveCheck, auth.aliveCheck)
                && Objects.equals(cookies, auth.cookies)
                && Objects.equals(origins, auth.origins);
    }

    @Override
    public int hashCode() {
        return Objects.hash(steps, aliveCheck, cookies, concurrentReads, origins);
    }
}
