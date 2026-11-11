package dev.ledgerforge.merchant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MerchantProfileRepository extends JpaRepository<MerchantProfile, UUID> {

    Optional<MerchantProfile> findByUserId(UUID userId);

    List<MerchantProfile> findByUserIdIn(List<UUID> userIds);

    @Query("""
            select m from MerchantProfile m
            where m.status = 'ACTIVE'
              and (:q is null or lower(m.businessName) like :q or lower(m.category) like :q)
            order by m.businessName
            """)
    List<MerchantProfile> searchActive(String q);
}
