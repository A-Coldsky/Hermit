package dev.hermit.common.template;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FilterRegistry {

    private final Map<String, Filter> filters = new LinkedHashMap<>();

    public static FilterRegistry defaults() {
        return defaults(Clock.systemDefaultZone());
    }

    public static FilterRegistry defaults(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        FilterRegistry registry = new FilterRegistry();
        registry.register("md5", BuiltinFilters.hash("MD5"));
        registry.register("sha256", BuiltinFilters.hash("SHA-256"));
        registry.register("base64", BuiltinFilters.base64());
        registry.register("urlencode", BuiltinFilters.urlencode());
        registry.register("trim", BuiltinFilters.trim());
        registry.register("default", BuiltinFilters.defaultValue());
        registry.register("date", BuiltinFilters.date(clock));
        registry.register("rsa", BuiltinFilters.rsa());
        return registry;
    }

    public FilterRegistry register(String name, Filter filter) {
        filters.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(filter, "filter"));
        return this;
    }

    public Filter get(String name) {
        return filters.get(name);
    }
}
