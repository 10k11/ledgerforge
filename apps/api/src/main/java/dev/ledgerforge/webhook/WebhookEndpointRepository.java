package dev.ledgerforge.webhook;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID merchantId);

    Optional<WebhookEndpoint> findByIdAndMerchantIdAndDeletedAtIsNull(UUID id, UUID merchantId);

    long countByMerchantIdAndDeletedAtIsNull(UUID merchantId);
}
