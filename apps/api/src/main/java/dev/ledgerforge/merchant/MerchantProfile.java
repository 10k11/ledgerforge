package dev.ledgerforge.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_profiles")
public class MerchantProfile {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "business_name", nullable = false, length = 120)
    private String businessName;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "support_email", length = 254)
    private String supportEmail;

    @Column(length = 255)
    private String website;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantProfile() {}

    public MerchantProfile(
            UUID id, UUID userId, String businessName, String category, String supportEmail, Instant now) {
        this.id = id;
        this.userId = userId;
        this.businessName = businessName;
        this.category = category;
        this.supportEmail = supportEmail;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String businessName, String category, String supportEmail, String website, Instant now) {
        this.businessName = businessName;
        this.category = category;
        this.supportEmail = supportEmail;
        this.website = website;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getCategory() {
        return category;
    }

    public String getSupportEmail() {
        return supportEmail;
    }

    public String getWebsite() {
        return website;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
