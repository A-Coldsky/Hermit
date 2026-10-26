package dev.hermit.reef.retention;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "hermit.reef.retention-cron=0 30 4 * * *")
@Import(ReefTestConfig.class)
class RetentionScheduleTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("retention-cron").toString());
    }

    @Autowired
    ReefProperties properties;

    @Autowired
    List<ScheduledTaskHolder> scheduledTasks;

    @Test
    void schedulerUsesTheConfiguredCron() {
        assertThat(properties.getRetentionCron()).isEqualTo("0 30 4 * * *");
        assertThat(resolvedRetentionCron()).isEqualTo("0 30 4 * * *");
    }

    private String resolvedRetentionCron() {
        for (ScheduledTaskHolder holder : scheduledTasks) {
            for (var scheduledTask : holder.getScheduledTasks()) {
                if (scheduledTask.getTask() instanceof CronTask cron
                        && cron.getRunnable().toString().contains("purgeExpiredReports")) {
                    return cron.getExpression();
                }
            }
        }
        throw new AssertionError("retention cron task was not registered");
    }
}
