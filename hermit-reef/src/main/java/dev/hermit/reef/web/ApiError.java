package dev.hermit.reef.web;

public record ApiError(ReefErrorCode code, String message) {
}
