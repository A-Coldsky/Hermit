package dev.hermit.common.template;

import java.util.List;

@FunctionalInterface
public interface Filter {

    Object apply(Object value, List<Object> args);
}
