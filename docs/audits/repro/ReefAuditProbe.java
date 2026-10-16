package dev.hermit.audit;

import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.RollbackEvent;
import dev.hermit.reef.tide.ReportIngestService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Simulates retrying the same report after Reef committed but the response was lost. */
public final class ReefAuditProbe {
    public static void main(String[] args) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__init.sql"),
                new ClassPathResource("db/migration/V2__tide_reports.sql"),
                new ClassPathResource("db/migration/V3__rollback_event_id.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ReportIngestService ingest = new ReportIngestService(jdbc, Clock.systemUTC());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Instant at = Instant.now();
        ReportBatch batch = new ReportBatch(UUID.randomUUID().toString(), "1.0.0", at, List.of(), List.of(),
                List.of(new RollbackEvent(
                        "audit_probe", "1.1.0", "1.0.0", "RULE_MISMATCH", at, "audit-probe-event")));
        transaction.execute(status -> ingest.ingest(batch));
        transaction.execute(status -> ingest.ingest(batch));
        System.out.printf("ROLLBACK_RETRY: logicalEvents=1, storedRows=%d%n",
                jdbc.queryForObject("SELECT COUNT(*) FROM rollback_events", Integer.class));
        // The isolated in-memory database is reclaimed when this probe JVM exits.
    }
}
