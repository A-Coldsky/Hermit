package dev.hermit.reef.retention;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionJob {

    private final RetentionService retentionService;

    public RetentionJob(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${hermit.reef.retention-cron:0 0 3 * * *}", zone = "UTC")
    public void purgeExpiredReports() {
        retentionService.purge();
    }
}
