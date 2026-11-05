package dev.hermit.crab.engine;

/** 入参不符合能力 schema。 */
public final class InvalidInput extends RuntimeException {

    public InvalidInput(String message) {
        super(message);
    }
}
