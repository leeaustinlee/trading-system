package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Governance-only review row for moving Research/Shadow evidence into an auditable review state.
 * Safety contract: this table must not feed FinalDecisionEngine, BUY/SELL/ENTER, candidate gates,
 * risk gates, production scores, or tradable universe expansion.
 */
@Entity
@Table(name = "promotion_review_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "symbol", "theme_tag", "source"}),
        indexes = {
                @Index(name = "idx_promotion_review_item_date_status", columnList = "trading_date, current_status"),
                @Index(name = "idx_promotion_review_item_symbol_date", columnList = "symbol, trading_date"),
                @Index(name = "idx_promotion_review_item_theme_date", columnList = "theme_tag, trading_date"),
                @Index(name = "idx_promotion_review_item_source_date", columnList = "source, trading_date")
        })
public class PromotionReviewItemEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "symbol", nullable = false, length = 20) private String symbol;
    @Column(name = "stock_name", length = 120) private String stockName;
    @Column(name = "theme_tag", length = 120) private String themeTag;
    @Column(name = "source", nullable = false, length = 40) private String source;
    @Column(name = "research_role", length = 60) private String researchRole;
    @Column(name = "current_status", nullable = false, length = 60) private String currentStatus;
    @Column(name = "previous_status", length = 60) private String previousStatus;
    @Column(name = "review_reason", length = 1000) private String reviewReason;
    @Column(name = "evidence_score", precision = 12, scale = 4) private BigDecimal evidenceScore;
    @Column(name = "risk_blocker", nullable = false) private Boolean riskBlocker = false;
    @Column(name = "governance_blocker", nullable = false) private Boolean governanceBlocker = false;
    @Column(name = "lifecycle_stage", length = 40) private String lifecycleStage;
    @Column(name = "theme_importance_score", precision = 12, scale = 4) private BigDecimal themeImportanceScore;
    @Column(name = "tradable_score", precision = 12, scale = 4) private BigDecimal tradableScore;
    @Column(name = "radar_score", precision = 12, scale = 4) private BigDecimal radarScore;
    @Column(name = "replay_metric_score", precision = 12, scale = 4) private BigDecimal replayMetricScore;
    @Column(name = "explain_miss_reason", length = 1000) private String explainMissReason;
    @Column(name = "reviewer", length = 120) private String reviewer;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    @Column(name = "decision_reason", length = 1000) private String decisionReason;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false) private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getResearchRole() { return researchRole; }
    public void setResearchRole(String researchRole) { this.researchRole = researchRole; }
    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }
    public String getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
    public BigDecimal getEvidenceScore() { return evidenceScore; }
    public void setEvidenceScore(BigDecimal evidenceScore) { this.evidenceScore = evidenceScore; }
    public Boolean getRiskBlocker() { return riskBlocker; }
    public void setRiskBlocker(Boolean riskBlocker) { this.riskBlocker = riskBlocker; }
    public Boolean getGovernanceBlocker() { return governanceBlocker; }
    public void setGovernanceBlocker(Boolean governanceBlocker) { this.governanceBlocker = governanceBlocker; }
    public String getLifecycleStage() { return lifecycleStage; }
    public void setLifecycleStage(String lifecycleStage) { this.lifecycleStage = lifecycleStage; }
    public BigDecimal getThemeImportanceScore() { return themeImportanceScore; }
    public void setThemeImportanceScore(BigDecimal themeImportanceScore) { this.themeImportanceScore = themeImportanceScore; }
    public BigDecimal getTradableScore() { return tradableScore; }
    public void setTradableScore(BigDecimal tradableScore) { this.tradableScore = tradableScore; }
    public BigDecimal getRadarScore() { return radarScore; }
    public void setRadarScore(BigDecimal radarScore) { this.radarScore = radarScore; }
    public BigDecimal getReplayMetricScore() { return replayMetricScore; }
    public void setReplayMetricScore(BigDecimal replayMetricScore) { this.replayMetricScore = replayMetricScore; }
    public String getExplainMissReason() { return explainMissReason; }
    public void setExplainMissReason(String explainMissReason) { this.explainMissReason = explainMissReason; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
