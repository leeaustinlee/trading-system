package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Theme lifecycle replay/advisory state.
 *
 * Safety contract: lifecycle stage/score are replay-only and advisory-only. They must not
 * control BUY/SELL/ENTER, FinalDecisionEngine, candidate gates, risk gates, RR gates,
 * stop-loss, market gate, production scores, or tradable promotion.
 */
@Entity
@Table(name = "theme_lifecycle_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag"}),
        indexes = {
                @Index(name = "idx_theme_lifecycle_date_stage", columnList = "trading_date, stage"),
                @Index(name = "idx_theme_lifecycle_theme_date", columnList = "theme_tag, trading_date")
        })
public class ThemeLifecycleStateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "theme_tag", nullable = false, length = 100) private String themeTag;
    @Column(name = "stage", nullable = false, length = 40) private String stage;
    @Column(name = "previous_stage", length = 40) private String previousStage;
    @Column(name = "stage_changed", nullable = false) private Boolean stageChanged = false;
    @Column(name = "stage_confidence", precision = 8, scale = 4) private BigDecimal stageConfidence;
    @Column(name = "leader_count", nullable = false) private Integer leaderCount = 0;
    @Column(name = "breadth", nullable = false) private Integer breadth = 0;
    @Column(name = "volume_expansion", precision = 8, scale = 4) private BigDecimal volumeExpansion;
    @Column(name = "continuation_days", nullable = false) private Integer continuationDays = 0;
    @Column(name = "rotation_score", precision = 8, scale = 4) private BigDecimal rotationScore;
    @Column(name = "crowding_score", precision = 8, scale = 4) private BigDecimal crowdingScore;
    @Column(name = "limit_up_density", precision = 8, scale = 4) private BigDecimal limitUpDensity;
    @Column(name = "narrative_density", precision = 8, scale = 4) private BigDecimal narrativeDensity;
    @Column(name = "institutional_flow_score", precision = 8, scale = 4) private BigDecimal institutionalFlowScore;
    @Column(name = "lifecycle_score", precision = 8, scale = 4) private BigDecimal lifecycleScore;
    @Column(name = "score_json", columnDefinition = "json") private String scoreJson;
    @Column(name = "reason", length = 1000) private String reason;
    @Column(name = "recommended_playbook_json", columnDefinition = "json") private String recommendedPlaybookJson;
    @Column(name = "avoid_playbook_json", columnDefinition = "json") private String avoidPlaybookJson;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getPreviousStage() { return previousStage; }
    public void setPreviousStage(String previousStage) { this.previousStage = previousStage; }
    public Boolean getStageChanged() { return stageChanged; }
    public void setStageChanged(Boolean stageChanged) { this.stageChanged = stageChanged; }
    public BigDecimal getStageConfidence() { return stageConfidence; }
    public void setStageConfidence(BigDecimal stageConfidence) { this.stageConfidence = stageConfidence; }
    public Integer getLeaderCount() { return leaderCount; }
    public void setLeaderCount(Integer leaderCount) { this.leaderCount = leaderCount; }
    public Integer getBreadth() { return breadth; }
    public void setBreadth(Integer breadth) { this.breadth = breadth; }
    public BigDecimal getVolumeExpansion() { return volumeExpansion; }
    public void setVolumeExpansion(BigDecimal volumeExpansion) { this.volumeExpansion = volumeExpansion; }
    public Integer getContinuationDays() { return continuationDays; }
    public void setContinuationDays(Integer continuationDays) { this.continuationDays = continuationDays; }
    public BigDecimal getRotationScore() { return rotationScore; }
    public void setRotationScore(BigDecimal rotationScore) { this.rotationScore = rotationScore; }
    public BigDecimal getCrowdingScore() { return crowdingScore; }
    public void setCrowdingScore(BigDecimal crowdingScore) { this.crowdingScore = crowdingScore; }
    public BigDecimal getLimitUpDensity() { return limitUpDensity; }
    public void setLimitUpDensity(BigDecimal limitUpDensity) { this.limitUpDensity = limitUpDensity; }
    public BigDecimal getNarrativeDensity() { return narrativeDensity; }
    public void setNarrativeDensity(BigDecimal narrativeDensity) { this.narrativeDensity = narrativeDensity; }
    public BigDecimal getInstitutionalFlowScore() { return institutionalFlowScore; }
    public void setInstitutionalFlowScore(BigDecimal institutionalFlowScore) { this.institutionalFlowScore = institutionalFlowScore; }
    public BigDecimal getLifecycleScore() { return lifecycleScore; }
    public void setLifecycleScore(BigDecimal lifecycleScore) { this.lifecycleScore = lifecycleScore; }
    public String getScoreJson() { return scoreJson; }
    public void setScoreJson(String scoreJson) { this.scoreJson = scoreJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRecommendedPlaybookJson() { return recommendedPlaybookJson; }
    public void setRecommendedPlaybookJson(String recommendedPlaybookJson) { this.recommendedPlaybookJson = recommendedPlaybookJson; }
    public String getAvoidPlaybookJson() { return avoidPlaybookJson; }
    public void setAvoidPlaybookJson(String avoidPlaybookJson) { this.avoidPlaybookJson = avoidPlaybookJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
