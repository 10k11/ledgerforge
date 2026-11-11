package dev.ledgerforge.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges expired idempotency records in bounded batches. Retention is {@code app.idempotency.ttl}
 * (24h by default), comfortably longer than any sensible client retry window. Ledger transactions
 * keep their idempotency key as a plain column, so purging records never breaks history.
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyCleanupJob(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:PT10M}", initialDelayString = "PT1M")
    public void purgeExpired() {
        int deleted = jdbc.update("""
                DELETE FROM idempotency_records
                 WHERE id IN (SELECT id FROM idempotency_records WHERE expires_at < ? LIMIT 5000)
                """, Timestamp.from(clock.instant()));
        if (deleted > 0) {
            log.info("Purged {} expired idempotency records", deleted);
        }
    }
}
