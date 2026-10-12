package dev.hermit.common.validate;

public record ValidationIssue(String code, String path, String message) {
}
