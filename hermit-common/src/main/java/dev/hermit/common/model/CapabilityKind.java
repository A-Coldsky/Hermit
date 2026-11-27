package dev.hermit.common.model;

/**
 * 能力的三类。{@code SENSITIVE} 先于 {@code READ} 判断：又写只读又写 {@code mcp.destructive: true} 的按更严的一类算。
 */
public enum CapabilityKind {
    READ("read"),
    WRITE("write"),
    SENSITIVE("sensitive");

    private final String wire;

    CapabilityKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 给 {@code other} 这一类用的入口能不能调本类的能力：更严的入口可以调更松的能力。 */
    public boolean allowedBy(CapabilityKind entry) {
        return entry.ordinal() >= ordinal();
    }
}
