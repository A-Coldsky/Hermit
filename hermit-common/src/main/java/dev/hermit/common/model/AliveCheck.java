package dev.hermit.common.model;

import java.util.Map;

public record AliveCheck(Map<String, Object> request, String expectNotRedirect) {
    public AliveCheck {
        request = request == null ? Map.of() : Frozen.map(request);
    }
}
