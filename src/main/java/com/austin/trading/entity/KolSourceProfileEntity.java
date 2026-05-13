package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kol_source_profile",
        uniqueConstraints = @UniqueConstraint(columnNames = "source_key"))
public class KolSourceProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_key", nullable = false, length = 120)
    private String sourceKey;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "credibility_weight", nullable = false, precision = 6, scale = 3)
    private BigDecimal credibilityWeight = BigDecimal.ONE;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public BigDecimal getCredibilityWeight() { return credibilityWeight; }
    public void setCredibilityWeight(BigDecimal credibilityWeight) { this.credibilityWeight = credibilityWeight; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
