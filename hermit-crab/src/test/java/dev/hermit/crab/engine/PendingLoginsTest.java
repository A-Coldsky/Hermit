package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.store.Origin;

class PendingLoginsTest {

    @Test
    void keepsSixtyFourAndDropsTheOldest() {
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        PendingLogins pending = new PendingLogins(clock);
        SessionKey first = key("a0");
        pending.store(first, draft("image", true));
        for (int i = 1; i < PendingLogins.MAX_PENDING; i++) {
            clock.advance(Duration.ofMillis(1));
            pending.store(key("a" + i), draft("image", true));
        }
        assertThat(pending.size()).isEqualTo(64);
        assertThat(pending.current(first)).isNotNull();
        clock.advance(Duration.ofMillis(1));
        pending.store(key("overflow"), draft("image", true));
        assertThat(pending.size()).isEqualTo(64);
        assertThat(pending.current(first)).isNull();
        assertThat(pending.current(key("overflow"))).isNotNull();
        assertThat(pending.current(key("a1"))).isNotNull();
    }

    @Test
    void imageResendWaitsTwoSecondsAndStopsAtTen() {
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        PendingLogins pending = new PendingLogins(clock);
        SessionKey key = key("alice");
        pending.store(key, draft("image", true));
        PendingLogins.View cooling = pending.view(key);
        assertThat(cooling.canResend()).isTrue();
        assertThat(cooling.resendAfterSeconds()).isEqualTo(2);
        PendingLogins.ResendDecision early = pending.beginResend(key);
        assertThat(early.accepted()).isFalse();
        assertThat(early.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(early.message()).isEqualTo(LoginInputMessages.TOO_FAST);
        assertThat(pending.current(key).resendCount()).isZero();

        clock.advance(Duration.ofSeconds(2));
        assertThat(pending.view(key).resendAfterSeconds()).isZero();
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                clock.advance(Duration.ofSeconds(2));
            }
            PendingLogins.ResendDecision decision = pending.beginResend(key);
            assertThat(decision.accepted()).isTrue();
            assertThat(pending.completeResend(key, decision.pending().id(), new byte[] {1, (byte) i}, "image/png"))
                    .isTrue();
        }
        clock.advance(Duration.ofSeconds(2));
        PendingLogins.View exhausted = pending.view(key);
        assertThat(exhausted.canResend()).isFalse();
        assertThat(exhausted.resendAfterSeconds()).isZero();
        PendingLogins.ResendDecision over = pending.beginResend(key);
        assertThat(over.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(over.message()).isEqualTo(LoginInputMessages.QUOTA);
        assertThat(pending.current(key).resendCount()).isEqualTo(10);
        assertThat(pending.image(key).bytes()).isEqualTo(new byte[] {1, 9});
    }

    @Test
    void codeResendWaitsSixtySecondsAndStopsAtThree() {
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        PendingLogins pending = new PendingLogins(clock);
        SessionKey key = key("alice");
        pending.store(key, draft("code", true));
        assertThat(pending.view(key).canResend()).isTrue();
        assertThat(pending.view(key).resendAfterSeconds()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(59));
        assertThat(pending.beginResend(key).message()).isEqualTo(LoginInputMessages.TOO_FAST);
        assertThat(pending.view(key).canResend()).isTrue();
        assertThat(pending.view(key).resendAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                clock.advance(Duration.ofSeconds(60));
            }
            assertThat(pending.beginResend(key).accepted()).isTrue();
            assertThat(pending.completeResend(key, pending.current(key).id(), null, null)).isTrue();
        }
        clock.advance(Duration.ofSeconds(60));
        assertThat(pending.view(key).canResend()).isFalse();
        assertThat(pending.beginResend(key).message()).isEqualTo(LoginInputMessages.QUOTA);
    }

    @Test
    void codeWithoutAPreviousRequestCannotResend() {
        PendingLogins pending = new PendingLogins(new EngineTestSupport.MutableClock());
        SessionKey key = key("alice");
        pending.store(key, draft("code", false));
        assertThat(pending.view(key).canResend()).isFalse();
        assertThat(pending.beginResend(key).message()).isEqualTo(LoginInputMessages.NO_RESEND);
    }

    private static SessionKey key(String account) {
        return new SessionKey(Origin.OFFICIAL, "captcha_oa", account);
    }

    private static PendingLogins.Draft draft(String kind, boolean previousRequest) {
        return new PendingLogins.Draft(
                "1.0.0",
                "hash",
                1,
                "captcha",
                kind,
                "验证码",
                null,
                300,
                previousRequest,
                Map.of(),
                "image".equals(kind) ? new byte[] {1, 2, 3, 4} : null,
                "image".equals(kind) ? "image/png" : null);
    }
}
