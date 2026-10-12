package dev.hermit.common.model;

public record Example(Object input, Object output) {
    public Example {
        input = Frozen.tree(input);
        output = Frozen.tree(output);
    }
}
