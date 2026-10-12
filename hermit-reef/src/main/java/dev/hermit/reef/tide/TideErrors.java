package dev.hermit.reef.tide;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;

final class TideErrors {

    private TideErrors() {
    }

    static ApiException unprocessable() {
        return new ApiException(422, ReefErrorCode.INVALID_INPUT, ReefErrorCode.INVALID_INPUT.message());
    }

    static ApiException tooLarge() {
        return new ApiException(413, ReefErrorCode.INVALID_INPUT, ReefErrorCode.INVALID_INPUT.message());
    }

    static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }
}
