package dev.hermit.crab.gateway;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

/** Local gateway body cap. Larger requests become {@code INVALID_INPUT}, not 413. */
public final class RequestBodies {
    public static final int MAX_BYTES = 1_000_000;

    private RequestBodies() {
    }

    public static byte[] read(HttpServletRequest request) throws IOException {
        long length = request.getContentLengthLong();
        if (length > MAX_BYTES) {
            throw new BodyTooLargeException();
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new BodyTooLargeException();
        }
        return bytes;
    }

    public static final class BodyTooLargeException extends RuntimeException {
    }
}
