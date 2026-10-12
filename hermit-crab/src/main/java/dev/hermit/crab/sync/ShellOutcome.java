package dev.hermit.crab.sync;

public record ShellOutcome(String shell, String version, OutcomeKind kind, String reason) {
}
