package dev.ledgerforge.webhook;

import dev.ledgerforge.common.config.AppProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox events by fanning them out into one webhook delivery per subscribed,
 * enabled endpoint of the event's merchant.
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so multiple application instances can
 * run the dispatcher concurrently without double-processing. Fan-out is idempotent thanks to the
 * unique (event_id, endpoint_id) constraint, so a crash between insert and status update is
 * harmless.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AppProperties.Webhooks config;
    private final Clock clock;

    public OutboxDispatcher(JdbcTemplate jdbc, PlatformTransactionManager txManager, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.config = props.webhooks();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.webhooks.poll-interval-ms:1000}")
    public void scheduled() {
        if (config.workersEnabled()) {
            dispatchBatch();
        }
    }

    /** Processes one batch; returns the number of events published. */
    public int dispatchBatch() {
        Integer published = tx.execute(status -> {
            Instant now = clock.instant();
            List<Object[]> events = jdbc.query(
                    """
                    SELECT id, event_type, merchant_id FROM outbox_events
                     WHERE status = 'PENDING'
                     ORDER BY created_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """,
                    (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)},
                    config.batchSize() * 4);
            for (Object[] e : events) {
                UUID eventId = (UUID) e[0];
                String type = (String) e[1];
                UUID merchantId = (UUID) e[2];
                if (merchantId != null) {
                    int fanned = jdbc.update("""
                            INSERT INTO webhook_deliveries
                                (id, event_id, endpoint_id, status, attempt_count, replay_count, next_attempt_at, created_at)
                            SELECT gen_random_uuid(), ?, w.id, 'PENDING', 0, 0, ?, ?
                              FROM webhook_endpoints w
                             WHERE w.merchant_id = ? AND w.enabled AND w.deleted_at IS NULL
                               AND ? = ANY (w.subscribed_events)
                            ON CONFLICT (event_id, endpoint_id) DO NOTHING
                            """, eventId, Timestamp.from(now), Timestamp.from(now), merchantId, type);
                    if (fanned > 0) {
                        log.debug("Event {} ({}) fanned out to {} endpoint(s)", eventId, type, fanned);
                    }
                }
                jdbc.update(
                        "UPDATE outbox_events SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                        Timestamp.from(now),
                        eventId);
            }
            return events.size();
        });
        return published == null ? 0 : published;
    }
}
