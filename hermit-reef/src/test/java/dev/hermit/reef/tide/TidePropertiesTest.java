package dev.hermit.reef.tide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TidePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableTide.class);

    @EnableConfigurationProperties(TideProperties.class)
    static class EnableTide {
    }

    @ParameterizedTest
    @CsvSource({
            "new-version-min-calls, -1, new-version-min-calls must be positive",
            "new-version-min-calls, 0, new-version-min-calls must be positive",
            "regression-delta, -0.01, regression-delta must be >= 0",
            "no-baseline-failure-rate, -0.50, no-baseline-failure-rate must be >= 0",
            "baseline-window-days, -1, baseline-window-days must be positive",
            "baseline-window-days, 0, baseline-window-days must be positive",
            "surge-window-minutes, -15, surge-window-minutes must be positive",
            "surge-window-minutes, 0, surge-window-minutes must be positive",
            "surge-min-count, -1, surge-min-count must be positive",
            "surge-min-count, 0, surge-min-count must be positive",
            "quiet-hours, -1, quiet-hours must be >= 0",
            "dedup-hours, -6, dedup-hours must be positive",
            "dedup-hours, 0, dedup-hours must be positive"
    })
    void illegalNumbersFailStartup(String key, String value, String message) {
        runner.withPropertyValues("hermit.reef.tide." + key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(message);
        });
    }

    @Test
    void zeroRatesAndQuietHoursStillStart() {
        runner.withPropertyValues(
                        "hermit.reef.tide.new-version-min-calls=1",
                        "hermit.reef.tide.regression-delta=0",
                        "hermit.reef.tide.no-baseline-failure-rate=0",
                        "hermit.reef.tide.baseline-window-days=1",
                        "hermit.reef.tide.surge-window-minutes=1",
                        "hermit.reef.tide.surge-min-count=1",
                        "hermit.reef.tide.quiet-hours=0",
                        "hermit.reef.tide.dedup-hours=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TideProperties properties = context.getBean(TideProperties.class);
                    assertThat(properties.getNewVersionMinCalls()).isEqualTo(1);
                    assertThat(properties.getRegressionDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(properties.getNoBaselineFailureRate()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(properties.getBaselineWindowDays()).isEqualTo(1);
                    assertThat(properties.getSurgeWindowMinutes()).isEqualTo(1);
                    assertThat(properties.getSurgeMinCount()).isEqualTo(1);
                    assertThat(properties.getQuietHours()).isZero();
                    assertThat(properties.getDedupHours()).isEqualTo(1);
                });
    }

    @Test
    void missingRatesAreRejected() {
        TideProperties missingDelta = new TideProperties();
        missingDelta.setRegressionDelta(null);
        assertThatThrownBy(missingDelta::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regression-delta");

        TideProperties missingRate = new TideProperties();
        missingRate.setNoBaselineFailureRate(null);
        assertThatThrownBy(missingRate::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-baseline-failure-rate");
    }
}
