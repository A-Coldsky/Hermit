package dev.hermit.reef.tide;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hermit.reef.tide")
public class TideProperties {

    private int newVersionMinCalls = 20;
    private BigDecimal regressionDelta = new BigDecimal("0.20");
    private BigDecimal noBaselineFailureRate = new BigDecimal("0.50");
    private int baselineWindowDays = 7;
    private int surgeWindowMinutes = 15;
    private int surgeMinCount = 10;
    private int quietHours = 24;
    private int dedupHours = 6;
    private String webhookUrl = "";
    private String evaluateCron = "0 */5 * * * *";

    @PostConstruct
    void validate() {
        requireAtLeast(newVersionMinCalls, 1, "hermit.reef.tide.new-version-min-calls must be positive");
        if (regressionDelta == null || regressionDelta.signum() < 0) {
            throw new IllegalStateException("hermit.reef.tide.regression-delta must be >= 0");
        }
        if (noBaselineFailureRate == null || noBaselineFailureRate.signum() < 0) {
            throw new IllegalStateException("hermit.reef.tide.no-baseline-failure-rate must be >= 0");
        }
        if (quietHours < 0) {
            throw new IllegalStateException("hermit.reef.tide.quiet-hours must be >= 0");
        }
        requireAtLeast(baselineWindowDays, 1, "hermit.reef.tide.baseline-window-days must be positive");
        requireAtLeast(surgeWindowMinutes, 1, "hermit.reef.tide.surge-window-minutes must be positive");
        requireAtLeast(surgeMinCount, 1, "hermit.reef.tide.surge-min-count must be positive");
        requireAtLeast(dedupHours, 1, "hermit.reef.tide.dedup-hours must be positive");
    }

    private static void requireAtLeast(int value, int minimum, String message) {
        if (value < minimum) {
            throw new IllegalStateException(message);
        }
    }

    public String webhookUrlOrNull() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        return webhookUrl;
    }

    public int getNewVersionMinCalls() {
        return newVersionMinCalls;
    }

    public void setNewVersionMinCalls(int newVersionMinCalls) {
        this.newVersionMinCalls = newVersionMinCalls;
    }

    public BigDecimal getRegressionDelta() {
        return regressionDelta;
    }

    public void setRegressionDelta(BigDecimal regressionDelta) {
        this.regressionDelta = regressionDelta;
    }

    public BigDecimal getNoBaselineFailureRate() {
        return noBaselineFailureRate;
    }

    public void setNoBaselineFailureRate(BigDecimal noBaselineFailureRate) {
        this.noBaselineFailureRate = noBaselineFailureRate;
    }

    public int getBaselineWindowDays() {
        return baselineWindowDays;
    }

    public void setBaselineWindowDays(int baselineWindowDays) {
        this.baselineWindowDays = baselineWindowDays;
    }

    public int getSurgeWindowMinutes() {
        return surgeWindowMinutes;
    }

    public void setSurgeWindowMinutes(int surgeWindowMinutes) {
        this.surgeWindowMinutes = surgeWindowMinutes;
    }

    public int getSurgeMinCount() {
        return surgeMinCount;
    }

    public void setSurgeMinCount(int surgeMinCount) {
        this.surgeMinCount = surgeMinCount;
    }

    public int getQuietHours() {
        return quietHours;
    }

    public void setQuietHours(int quietHours) {
        this.quietHours = quietHours;
    }

    public int getDedupHours() {
        return dedupHours;
    }

    public void setDedupHours(int dedupHours) {
        this.dedupHours = dedupHours;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getEvaluateCron() {
        return evaluateCron;
    }

    public void setEvaluateCron(String evaluateCron) {
        this.evaluateCron = evaluateCron;
    }
}
