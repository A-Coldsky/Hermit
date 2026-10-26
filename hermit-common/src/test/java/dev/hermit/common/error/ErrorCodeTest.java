package dev.hermit.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void listsTheStableCodes() {
        assertThat(ErrorCode.values()).containsExactly(
                ErrorCode.INVALID_INPUT,
                ErrorCode.AUTH_FAILED,
                ErrorCode.SESSION_EXPIRED,
                ErrorCode.UPSTREAM_ERROR,
                ErrorCode.RULE_MISMATCH,
                ErrorCode.TIMEOUT,
                ErrorCode.NOT_CONFIGURED,
                ErrorCode.ACCOUNT_NOT_FOUND,
                ErrorCode.FORBIDDEN,
                ErrorCode.NOT_FOUND,
                ErrorCode.CONCH_DISABLED,
                ErrorCode.INTERNAL);
    }
}
