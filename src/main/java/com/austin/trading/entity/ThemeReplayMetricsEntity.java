package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Replay/analytics-only metrics for Theme-first research validation.
 * Must never feed FinalDecisionEngine, BUY/SELL/ENTER, risk gates, production ranking, or auto-promotion.
 */
@Entity
@Table(name = "theme_replay_metrics",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag"}),
        indexes = {
                @Index(name = "idx_theme_replay_metrics_date", columnList = "trading_date"),
                @Index(name = "idx_theme_replay_metrics_theme_date", columnList = "theme_tag, trading_date")
        })
public class ThemeReplayMetricsEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "theme_tag", nullable = false, length = 100) private String themeTag;

    @Column(name = "leader_retention_rate", precision = 8, scale = 4) private BigDecimal leaderRetentionRate;
    @Column(name = "peer_discovery_hit_rate", precision = 8, scale = 4) private BigDecimal peerDiscoveryHitRate;
    @Column(name = "taxonomy_gap_discovery_count", nullable = false) private Integer taxonomyGapDiscoveryCount = 0;
    @Column(name = "research_universe_coverage", precision = 8, scale = 4) private BigDecimal researchUniverseCoverage;
    @Column(name = "candidate_diversification", nullable = false) private Integer candidateDiversification = 0;

    @Column(name = "risk_rejected_leader_count", nullable = false) private Integer riskRejectedLeaderCount = 0;
    @Column(name = "false_promotion_count", nullable = false) private Integer falsePromotionCount = 0;
    @Column(name = "chase_high_avoided_count", nullable = false) private Integer chaseHighAvoidedCount = 0;
    @Column(name = "risk_gate_bypass_count", nullable = false) private Integer riskGateBypassCount = 0;
    @Column(name = "leadership_only_entered_count", nullable = false) private Integer leadershipOnlyEnteredCount = 0;
    @Column(name = "leader_tradable_false_enter_count", nullable = false) private Integer leaderTradableFalseEnterCount = 0;
    @Column(name = "peer_shadow_direct_promotion_count", nullable = false) private Integer peerShadowDirectPromotionCount = 0;
    @Column(name = "narrative_direct_enter_count", nullable = false) private Integer narrativeDirectEnterCount = 0;
    @Column(name = "research_vs_tradable_separation_violation_count", nullable = false) private Integer researchVsTradableSeparationViolationCount = 0;

    @Column(name = "post_signal_return_1d", precision = 10, scale = 4) private BigDecimal postSignalReturn1d;
    @Column(name = "post_signal_return_3d", precision = 10, scale = 4) private BigDecimal postSignalReturn3d;
    @Column(name = "post_signal_return_5d", precision = 10, scale = 4) private BigDecimal postSignalReturn5d;
    @Column(name = "max_drawdown_after_signal", precision = 10, scale = 4) private BigDecimal maxDrawdownAfterSignal;
    @Column(name = "pullback_entry_return", precision = 10, scale = 4) private BigDecimal pullbackEntryReturn;
    @Column(name = "breakout_entry_return", precision = 10, scale = 4) private BigDecimal breakoutEntryReturn;
    @Column(name = "low_base_follower_return", precision = 10, scale = 4) private BigDecimal lowBaseFollowerReturn;

    @Column(name = "stage_transition_accuracy", precision = 8, scale = 4) private BigDecimal stageTransitionAccuracy;
    @Column(name = "emerging_to_mainstream_hit_rate", precision = 8, scale = 4) private BigDecimal emergingToMainstreamHitRate;
    @Column(name = "overheated_avoidance_return", precision = 10, scale = 4) private BigDecimal overheatedAvoidanceReturn;
    @Column(name = "distribution_warning_lead_time", precision = 10, scale = 4) private BigDecimal distributionWarningLeadTime;
    @Column(name = "dead_theme_false_positive_rate", precision = 8, scale = 4) private BigDecimal deadThemeFalsePositiveRate;

    @Column(name = "ai_governance_annotated_rate", precision = 8, scale = 4) private BigDecimal aiGovernanceAnnotatedRate;
    @Column(name = "rejection_reason_coverage", precision = 8, scale = 4) private BigDecimal rejectionReasonCoverage;
    @Column(name = "final_decision_trace_coverage", precision = 8, scale = 4) private BigDecimal finalDecisionTraceCoverage;

    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public BigDecimal getLeaderRetentionRate() { return leaderRetentionRate; }
    public void setLeaderRetentionRate(BigDecimal leaderRetentionRate) { this.leaderRetentionRate = leaderRetentionRate; }
    public BigDecimal getPeerDiscoveryHitRate() { return peerDiscoveryHitRate; }
    public void setPeerDiscoveryHitRate(BigDecimal peerDiscoveryHitRate) { this.peerDiscoveryHitRate = peerDiscoveryHitRate; }
    public Integer getTaxonomyGapDiscoveryCount() { return taxonomyGapDiscoveryCount; }
    public void setTaxonomyGapDiscoveryCount(Integer taxonomyGapDiscoveryCount) { this.taxonomyGapDiscoveryCount = taxonomyGapDiscoveryCount; }
    public BigDecimal getResearchUniverseCoverage() { return researchUniverseCoverage; }
    public void setResearchUniverseCoverage(BigDecimal researchUniverseCoverage) { this.researchUniverseCoverage = researchUniverseCoverage; }
    public Integer getCandidateDiversification() { return candidateDiversification; }
    public void setCandidateDiversification(Integer candidateDiversification) { this.candidateDiversification = candidateDiversification; }
    public Integer getRiskRejectedLeaderCount() { return riskRejectedLeaderCount; }
    public void setRiskRejectedLeaderCount(Integer riskRejectedLeaderCount) { this.riskRejectedLeaderCount = riskRejectedLeaderCount; }
    public Integer getFalsePromotionCount() { return falsePromotionCount; }
    public void setFalsePromotionCount(Integer falsePromotionCount) { this.falsePromotionCount = falsePromotionCount; }
    public Integer getChaseHighAvoidedCount() { return chaseHighAvoidedCount; }
    public void setChaseHighAvoidedCount(Integer chaseHighAvoidedCount) { this.chaseHighAvoidedCount = chaseHighAvoidedCount; }
    public Integer getRiskGateBypassCount() { return riskGateBypassCount; }
    public void setRiskGateBypassCount(Integer riskGateBypassCount) { this.riskGateBypassCount = riskGateBypassCount; }
    public Integer getLeadershipOnlyEnteredCount() { return leadershipOnlyEnteredCount; }
    public void setLeadershipOnlyEnteredCount(Integer leadershipOnlyEnteredCount) { this.leadershipOnlyEnteredCount = leadershipOnlyEnteredCount; }
    public Integer getLeaderTradableFalseEnterCount() { return leaderTradableFalseEnterCount; }
    public void setLeaderTradableFalseEnterCount(Integer leaderTradableFalseEnterCount) { this.leaderTradableFalseEnterCount = leaderTradableFalseEnterCount; }
    public Integer getPeerShadowDirectPromotionCount() { return peerShadowDirectPromotionCount; }
    public void setPeerShadowDirectPromotionCount(Integer peerShadowDirectPromotionCount) { this.peerShadowDirectPromotionCount = peerShadowDirectPromotionCount; }
    public Integer getNarrativeDirectEnterCount() { return narrativeDirectEnterCount; }
    public void setNarrativeDirectEnterCount(Integer narrativeDirectEnterCount) { this.narrativeDirectEnterCount = narrativeDirectEnterCount; }
    public Integer getResearchVsTradableSeparationViolationCount() { return researchVsTradableSeparationViolationCount; }
    public void setResearchVsTradableSeparationViolationCount(Integer researchVsTradableSeparationViolationCount) { this.researchVsTradableSeparationViolationCount = researchVsTradableSeparationViolationCount; }
    public BigDecimal getPostSignalReturn1d() { return postSignalReturn1d; }
    public void setPostSignalReturn1d(BigDecimal postSignalReturn1d) { this.postSignalReturn1d = postSignalReturn1d; }
    public BigDecimal getPostSignalReturn3d() { return postSignalReturn3d; }
    public void setPostSignalReturn3d(BigDecimal postSignalReturn3d) { this.postSignalReturn3d = postSignalReturn3d; }
    public BigDecimal getPostSignalReturn5d() { return postSignalReturn5d; }
    public void setPostSignalReturn5d(BigDecimal postSignalReturn5d) { this.postSignalReturn5d = postSignalReturn5d; }
    public BigDecimal getMaxDrawdownAfterSignal() { return maxDrawdownAfterSignal; }
    public void setMaxDrawdownAfterSignal(BigDecimal maxDrawdownAfterSignal) { this.maxDrawdownAfterSignal = maxDrawdownAfterSignal; }
    public BigDecimal getPullbackEntryReturn() { return pullbackEntryReturn; }
    public void setPullbackEntryReturn(BigDecimal pullbackEntryReturn) { this.pullbackEntryReturn = pullbackEntryReturn; }
    public BigDecimal getBreakoutEntryReturn() { return breakoutEntryReturn; }
    public void setBreakoutEntryReturn(BigDecimal breakoutEntryReturn) { this.breakoutEntryReturn = breakoutEntryReturn; }
    public BigDecimal getLowBaseFollowerReturn() { return lowBaseFollowerReturn; }
    public void setLowBaseFollowerReturn(BigDecimal lowBaseFollowerReturn) { this.lowBaseFollowerReturn = lowBaseFollowerReturn; }
    public BigDecimal getStageTransitionAccuracy() { return stageTransitionAccuracy; }
    public void setStageTransitionAccuracy(BigDecimal stageTransitionAccuracy) { this.stageTransitionAccuracy = stageTransitionAccuracy; }
    public BigDecimal getEmergingToMainstreamHitRate() { return emergingToMainstreamHitRate; }
    public void setEmergingToMainstreamHitRate(BigDecimal emergingToMainstreamHitRate) { this.emergingToMainstreamHitRate = emergingToMainstreamHitRate; }
    public BigDecimal getOverheatedAvoidanceReturn() { return overheatedAvoidanceReturn; }
    public void setOverheatedAvoidanceReturn(BigDecimal overheatedAvoidanceReturn) { this.overheatedAvoidanceReturn = overheatedAvoidanceReturn; }
    public BigDecimal getDistributionWarningLeadTime() { return distributionWarningLeadTime; }
    public void setDistributionWarningLeadTime(BigDecimal distributionWarningLeadTime) { this.distributionWarningLeadTime = distributionWarningLeadTime; }
    public BigDecimal getDeadThemeFalsePositiveRate() { return deadThemeFalsePositiveRate; }
    public void setDeadThemeFalsePositiveRate(BigDecimal deadThemeFalsePositiveRate) { this.deadThemeFalsePositiveRate = deadThemeFalsePositiveRate; }
    public BigDecimal getAiGovernanceAnnotatedRate() { return aiGovernanceAnnotatedRate; }
    public void setAiGovernanceAnnotatedRate(BigDecimal aiGovernanceAnnotatedRate) { this.aiGovernanceAnnotatedRate = aiGovernanceAnnotatedRate; }
    public BigDecimal getRejectionReasonCoverage() { return rejectionReasonCoverage; }
    public void setRejectionReasonCoverage(BigDecimal rejectionReasonCoverage) { this.rejectionReasonCoverage = rejectionReasonCoverage; }
    public BigDecimal getFinalDecisionTraceCoverage() { return finalDecisionTraceCoverage; }
    public void setFinalDecisionTraceCoverage(BigDecimal finalDecisionTraceCoverage) { this.finalDecisionTraceCoverage = finalDecisionTraceCoverage; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
