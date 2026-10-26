package dev.hermit.crab.sync;

public record ConchOutcome(String conch, String version, OutcomeKind kind, String reason) {
}
