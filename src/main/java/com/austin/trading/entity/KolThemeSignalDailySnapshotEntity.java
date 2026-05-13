package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "kol_theme_signal_daily_snapshot",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag", "direction"}))
public class KolThemeSignalDailySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "theme_tag", nullable = false, length = 120)
    private String themeTag;

    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    @Column(name = "source_count", nullable = false)
    private Integer sourceCount = 0;

    @Column(name = "evidence_count", nullable = false)
    private Integer evidenceCount = 0;

    @Column(name = "positive_score", nullable = false, precision = 8, scale = 4)
    private BigDecimal positiveScore = BigDecimal.ZERO;

    @Column(name = "negative_score", nullable = false, precision = 8, scale = 4)
    private BigDecimal negativeScore = BigDecimal.ZERO;

    @Column(name = "net_shadow_boost", nullable = false, precision = 8, scale = 4)
    private BigDecimal netShadowBoost = BigDecimal.ZERO;

    @Column(name = "crowding_risk", nullable = false, length = 20)
    private String crowdingRisk = "LOW";

    @Column(name = "top_sources_json", columnDefinition = "json")
    private String topSourcesJson;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Integer getSourceCount() { return sourceCount; }
    public void setSourceCount(Integer sourceCount) { this.sourceCount = sourceCount; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }
    public BigDecimal getPositiveScore() { return positiveScore; }
    public void setPositiveScore(BigDecimal positiveScore) { this.positiveScore = positiveScore; }
    public BigDecimal getNegativeScore() { return negativeScore; }
    public void setNegativeScore(BigDecimal negativeScore) { this.negativeScore = negativeScore; }
    public BigDecimal getNetShadowBoost() { return netShadowBoost; }
    public void setNetShadowBoost(BigDecimal netShadowBoost) { this.netShadowBoost = netShadowBoost; }
    public String getCrowdingRisk() { return crowdingRisk; }
    public void setCrowdingRisk(String crowdingRisk) { this.crowdingRisk = crowdingRisk; }
    public String getTopSourcesJson() { return topSourcesJson; }
    public void setTopSourcesJson(String topSourcesJson) { this.topSourcesJson = topSourcesJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
