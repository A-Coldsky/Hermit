package dev.hermit.common.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

public record Auth(List<Step> steps, AliveCheck aliveCheck, Map<String, String> cookies) {
    public Auth {
        steps = steps == null ? List.of() : List.copyOf(steps);
        cookies = cookies == null || cookies.isEmpty() ? Map.of() : Map.copyOf(cookies);
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
}
