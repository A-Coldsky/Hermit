package dev.hermit.reef.tide;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertJob {

    private final AlertService alertService;

    public AlertJob(AlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(cron = "${hermit.reef.tide.evaluate-cron:0 */5 * * * *}", zone = "UTC")
    public void evaluateAlerts() {
        alertService.evaluate();
    }
}
