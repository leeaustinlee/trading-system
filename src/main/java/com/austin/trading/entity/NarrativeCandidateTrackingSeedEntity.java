package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "narrative_candidate_tracking_seed",
        uniqueConstraints = @UniqueConstraint(columnNames = {"decision_date", "symbol", "related_theme"}))
public class NarrativeCandidateTrackingSeedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_date", nullable = false)
    private LocalDate decisionDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "related_theme", nullable = false, length = 120)
    private String relatedTheme;

    @Column(name = "lifecycle_at_detection", nullable = false, length = 40)
    private String lifecycleAtDetection;

    @Column(name = "attention_score", precision = 8, scale = 4)
    private BigDecimal attentionScore;

    @Column(name = "crowding_score", precision = 8, scale = 4)
    private BigDecimal crowdingScore;

    @Column(name = "shadow_delta", precision = 8, scale = 4)
    private BigDecimal shadowDelta;

    @Column(name = "base_score", precision = 8, scale = 4)
    private BigDecimal baseScore;

    @Column(name = "risk_flags_json", columnDefinition = "json")
    private String riskFlagsJson;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "weak_signal_only", nullable = false)
    private boolean weakSignalOnly = true;

    @Column(name = "production_decision_allowed", nullable = false)
    private boolean productionDecisionAllowed = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getDecisionDate() { return decisionDate; }
    public void setDecisionDate(LocalDate decisionDate) { this.decisionDate = decisionDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getRelatedTheme() { return relatedTheme; }
    public void setRelatedTheme(String relatedTheme) { this.relatedTheme = relatedTheme; }
    public String getLifecycleAtDetection() { return lifecycleAtDetection; }
    public void setLifecycleAtDetection(String lifecycleAtDetection) { this.lifecycleAtDetection = lifecycleAtDetection; }
    public BigDecimal getAttentionScore() { return attentionScore; }
    public void setAttentionScore(BigDecimal attentionScore) { this.attentionScore = attentionScore; }
    public BigDecimal getCrowdingScore() { return crowdingScore; }
    public void setCrowdingScore(BigDecimal crowdingScore) { this.crowdingScore = crowdingScore; }
    public BigDecimal getShadowDelta() { return shadowDelta; }
    public void setShadowDelta(BigDecimal shadowDelta) { this.shadowDelta = shadowDelta; }
    public BigDecimal getBaseScore() { return baseScore; }
    public void setBaseScore(BigDecimal baseScore) { this.baseScore = baseScore; }
    public String getRiskFlagsJson() { return riskFlagsJson; }
    public void setRiskFlagsJson(String riskFlagsJson) { this.riskFlagsJson = riskFlagsJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public boolean isWeakSignalOnly() { return weakSignalOnly; }
    public void setWeakSignalOnly(boolean weakSignalOnly) { this.weakSignalOnly = weakSignalOnly; }
    public boolean isProductionDecisionAllowed() { return productionDecisionAllowed; }
    public void setProductionDecisionAllowed(boolean productionDecisionAllowed) { this.productionDecisionAllowed = productionDecisionAllowed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
