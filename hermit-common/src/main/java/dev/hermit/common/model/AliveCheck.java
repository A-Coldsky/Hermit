package dev.hermit.common.model;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 探活请求配置。{@code expect} 是 1.12.0 起可选的正向预期条件（例如 {@code status}、{@code json_equals}），
 * 全部成立才算会话仍有效；未声明时不进规范 JSON，避免改动旧包的摘要。
 */
public record AliveCheck(
        Map<String, Object> request,
        String expectNotRedirect,
        Map<String, Object> expect,
        @JsonIgnore boolean expectDeclared
) {
    public AliveCheck {
        request = request == null ? Map.of() : Frozen.map(request);
        expect = expect == null || expect.isEmpty() ? Map.of() : Frozen.map(expect);
        expectDeclared = expectDeclared || !expect.isEmpty();
    }

    public AliveCheck(Map<String, Object> request, String expectNotRedirect) {
        this(request, expectNotRedirect, Map.of(), false);
    }

    public AliveCheck(Map<String, Object> request, String expectNotRedirect, Map<String, Object> expect) {
        this(request, expectNotRedirect, expect, expect != null && !expect.isEmpty());
    }

    /** 没写时不进规范 JSON，旧包的快照令牌不变。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Override
    public Map<String, Object> expect() {
        return expect;
    }

    @JsonIgnore
    @Override
    public boolean expectDeclared() {
        return expectDeclared;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AliveCheck alive)) {
            return false;
        }
        return Objects.equals(request, alive.request)
                && Objects.equals(expectNotRedirect, alive.expectNotRedirect)
                && Objects.equals(expect, alive.expect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(request, expectNotRedirect, expect);
    }
}
