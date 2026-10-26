package dev.hermit.reef.config;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

@ConfigurationProperties(prefix = "hermit.reef")
public class ReefProperties {

    static final String BLANK_AGENT_TOKEN = "hermit.reef.agent-token is blank. All /agent/** requests will be rejected. "
            + "Set HERMIT_REEF_AGENT_TOKEN.";

    private static final Logger log = LoggerFactory.getLogger(ReefProperties.class);

    private String dataDir = "./reef-data";
    private String agentToken = "";
    private Duration jwtTtl = Duration.ofHours(8);
    private String latestCrabVersion = "";
    private String crabDownloadUrl = "";
    private int reportRetentionDays = 90;
    private String retentionCron = "0 0 3 * * *";

    @PostConstruct
    void validate() {
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalStateException("hermit.reef.data-dir must not be blank");
        }
        if (jwtTtl == null || jwtTtl.isZero() || jwtTtl.isNegative()) {
            throw new IllegalStateException("hermit.reef.jwt-ttl must be positive");
        }
        if (reportRetentionDays < 1) {
            throw new IllegalStateException("hermit.reef.report-retention-days must be positive");
        }
        if (retentionCron == null || retentionCron.isBlank() || !CronExpression.isValidExpression(retentionCron)) {
            throw new IllegalStateException("hermit.reef.retention-cron must be a valid cron expression");
        }
        if (configuredAgentToken() == null) {
            log.warn(BLANK_AGENT_TOKEN);
        }
    }

    public Path dataDirectory() {
        return Path.of(dataDir);
    }

    public String configuredAgentToken() {
        return blankToNull(agentToken);
    }

    public String latestCrabVersionOrNull() {
        return blankToNull(latestCrabVersion);
    }

    public String crabDownloadUrlOrNull() {
        return blankToNull(crabDownloadUrl);
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getAgentToken() {
        return agentToken;
    }

    public void setAgentToken(String agentToken) {
        this.agentToken = agentToken;
    }

    public Duration getJwtTtl() {
        return jwtTtl;
    }

    public void setJwtTtl(Duration jwtTtl) {
        this.jwtTtl = jwtTtl;
    }

    public String getLatestCrabVersion() {
        return latestCrabVersion;
    }

    public void setLatestCrabVersion(String latestCrabVersion) {
        this.latestCrabVersion = latestCrabVersion;
    }

    public String getCrabDownloadUrl() {
        return crabDownloadUrl;
    }

    public void setCrabDownloadUrl(String crabDownloadUrl) {
        this.crabDownloadUrl = crabDownloadUrl;
    }

    public int getReportRetentionDays() {
        return reportRetentionDays;
    }

    public void setReportRetentionDays(int reportRetentionDays) {
        this.reportRetentionDays = reportRetentionDays;
    }

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
