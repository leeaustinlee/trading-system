package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "theme_replay_snapshot",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag"}),
        indexes = {
                @Index(name = "idx_theme_replay_snapshot_date", columnList = "trading_date"),
                @Index(name = "idx_theme_replay_snapshot_leader", columnList = "leader_symbol, trading_date")
        })
public class ThemeReplaySnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "theme_tag", nullable = false, length = 100) private String themeTag;
    @Column(name = "lifecycle_stage", length = 40) private String lifecycleStage;
    @Column(name = "leader_symbol", length = 20) private String leaderSymbol;
    @Column(name = "leader_count", nullable = false) private Integer leaderCount = 0;
    @Column(name = "peer_count", nullable = false) private Integer peerCount = 0;
    @Column(name = "breadth", nullable = false) private Integer breadth = 0;
    @Column(name = "taxonomy_gap_count", nullable = false) private Integer taxonomyGapCount = 0;
    @Column(name = "divergence_count", nullable = false) private Integer divergenceCount = 0;
    @Column(name = "risk_rejected_count", nullable = false) private Integer riskRejectedCount = 0;
    @Column(name = "research_universe_count", nullable = false) private Integer researchUniverseCount = 0;
    @Column(name = "tradable_universe_count", nullable = false) private Integer tradableUniverseCount = 0;
    @Column(name = "replay_score", precision = 8, scale = 4) private BigDecimal replayScore;
    @Column(name = "lifecycle_score", precision = 8, scale = 4) private BigDecimal lifecycleScore;
    @Column(name = "lifecycle_reason", length = 1000) private String lifecycleReason;
    @Column(name = "recommended_playbook_json", columnDefinition = "json") private String recommendedPlaybookJson;
    @Column(name = "avoid_playbook_json", columnDefinition = "json") private String avoidPlaybookJson;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getLifecycleStage() { return lifecycleStage; }
    public void setLifecycleStage(String lifecycleStage) { this.lifecycleStage = lifecycleStage; }
    public String getLeaderSymbol() { return leaderSymbol; }
    public void setLeaderSymbol(String leaderSymbol) { this.leaderSymbol = leaderSymbol; }
    public Integer getLeaderCount() { return leaderCount; }
    public void setLeaderCount(Integer leaderCount) { this.leaderCount = leaderCount; }
    public Integer getPeerCount() { return peerCount; }
    public void setPeerCount(Integer peerCount) { this.peerCount = peerCount; }
    public Integer getBreadth() { return breadth; }
    public void setBreadth(Integer breadth) { this.breadth = breadth; }
    public Integer getTaxonomyGapCount() { return taxonomyGapCount; }
    public void setTaxonomyGapCount(Integer taxonomyGapCount) { this.taxonomyGapCount = taxonomyGapCount; }
    public Integer getDivergenceCount() { return divergenceCount; }
    public void setDivergenceCount(Integer divergenceCount) { this.divergenceCount = divergenceCount; }
    public Integer getRiskRejectedCount() { return riskRejectedCount; }
    public void setRiskRejectedCount(Integer riskRejectedCount) { this.riskRejectedCount = riskRejectedCount; }
    public Integer getResearchUniverseCount() { return researchUniverseCount; }
    public void setResearchUniverseCount(Integer researchUniverseCount) { this.researchUniverseCount = researchUniverseCount; }
    public Integer getTradableUniverseCount() { return tradableUniverseCount; }
    public void setTradableUniverseCount(Integer tradableUniverseCount) { this.tradableUniverseCount = tradableUniverseCount; }
    public BigDecimal getReplayScore() { return replayScore; }
    public void setReplayScore(BigDecimal replayScore) { this.replayScore = replayScore; }
    public BigDecimal getLifecycleScore() { return lifecycleScore; }
    public void setLifecycleScore(BigDecimal lifecycleScore) { this.lifecycleScore = lifecycleScore; }
    public String getLifecycleReason() { return lifecycleReason; }
    public void setLifecycleReason(String lifecycleReason) { this.lifecycleReason = lifecycleReason; }
    public String getRecommendedPlaybookJson() { return recommendedPlaybookJson; }
    public void setRecommendedPlaybookJson(String recommendedPlaybookJson) { this.recommendedPlaybookJson = recommendedPlaybookJson; }
    public String getAvoidPlaybookJson() { return avoidPlaybookJson; }
    public void setAvoidPlaybookJson(String avoidPlaybookJson) { this.avoidPlaybookJson = avoidPlaybookJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
