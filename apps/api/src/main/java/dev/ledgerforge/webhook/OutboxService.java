package dev.ledgerforge.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Records domain events in the outbox as part of the caller's transaction. */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param merchantId the merchant whose webhook endpoints should receive the event, or null for
     *     events with no merchant audience (e.g. customer-to-customer transfers)
     * @param data event-specific payload; must not contain secrets or unnecessary personal data
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(
            String eventType, String aggregateType, UUID aggregateId, UUID merchantId, Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId.toString());
        envelope.put("type", eventType);
        envelope.put("createdAt", now.toString());
        envelope.put("livemode", false);
        envelope.put("data", data);
        OutboxEvent event = new OutboxEvent(
                eventId,
                eventType,
                aggregateType,
                aggregateId,
                merchantId,
                objectMapper.writeValueAsString(envelope),
                now);
        return repository.save(event);
    }
}
