package dev.hermit.reef.config;

import dev.hermit.common.version.SemVer;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

@ConfigurationProperties(prefix = "hermit.reef")
public class ReefProperties {

    static final String BLANK_DATA_DIR_MESSAGE = "hermit.reef.data-dir must not be blank";

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
            throw new IllegalStateException(BLANK_DATA_DIR_MESSAGE);
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
        if (latestCrabVersion != null && !latestCrabVersion.isBlank()) {
            try {
                SemVer.parse(latestCrabVersion);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("hermit.reef.latest-crab-version must be a semver");
            }
        }
        // 空配置令牌不在这里警告。库准备好之后由 AgentTokenWarnings 判断还有没有可用令牌。
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
        String value = blankToNull(crabDownloadUrl);
        // 控制台会把这个地址放进链接。不是 http/https 就不下发，避免 javascript: 在源站执行。不因此拒绝启动。
        if (value == null || !httpDownloadUrl(value)) {
            return null;
        }
        return value;
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

    /** 写成 scheme://，并且协议名是 http 或 https。 */
    private static boolean httpDownloadUrl(String value) {
        int separator = value.indexOf("://");
        if (separator <= 0) {
            return false;
        }
        String scheme = value.substring(0, separator);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!letter) {
                return false;
            }
        }
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
