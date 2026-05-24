package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "theme_replay_node",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag", "symbol"}),
        indexes = {
                @Index(name = "idx_theme_replay_node_date_theme", columnList = "trading_date, theme_tag"),
                @Index(name = "idx_theme_replay_node_role", columnList = "trading_date, research_role"),
                @Index(name = "idx_theme_replay_node_leader", columnList = "theme_leader_symbol, trading_date")
        })
public class ThemeReplayNodeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "theme_tag", nullable = false, length = 100) private String themeTag;
    @Column(name = "symbol", nullable = false, length = 20) private String symbol;
    @Column(name = "stock_name", length = 120) private String stockName;
    @Column(name = "research_role", nullable = false, length = 40) private String researchRole;
    @Column(name = "candidate_role", length = 40) private String candidateRole;
    @Column(name = "is_theme_leader", nullable = false) private Boolean isThemeLeader = false;
    @Column(name = "leadership_only", nullable = false) private Boolean leadershipOnly = false;
    @Column(name = "theme_leader_symbol", length = 20) private String themeLeaderSymbol;
    @Column(name = "research_universe", nullable = false) private Boolean researchUniverse = true;
    @Column(name = "tradable_universe", nullable = false) private Boolean tradableUniverse = false;
    @Column(name = "leader_tradable", nullable = false) private Boolean leaderTradable = false;
    @Column(name = "theme_importance_score", precision = 8, scale = 4) private BigDecimal themeImportanceScore;
    @Column(name = "tradable_score", precision = 8, scale = 4) private BigDecimal tradableScore;
    @Column(name = "shadow_rank_score", precision = 8, scale = 4) private BigDecimal shadowRankScore;
    @Column(name = "divergence_score", precision = 8, scale = 4) private BigDecimal divergenceScore;
    @Column(name = "taxonomy_gap_score", precision = 8, scale = 4) private BigDecimal taxonomyGapScore;
    @Column(name = "risk_rejected", nullable = false) private Boolean riskRejected = false;
    @Column(name = "rejection_reason", length = 500) private String rejectionReason;
    @Column(name = "safety_note", length = 500) private String safetyNote;
    @Column(name = "ai_governance_summary", length = 1000) private String aiGovernanceSummary;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getResearchRole() { return researchRole; }
    public void setResearchRole(String researchRole) { this.researchRole = researchRole; }
    public String getCandidateRole() { return candidateRole; }
    public void setCandidateRole(String candidateRole) { this.candidateRole = candidateRole; }
    public Boolean getIsThemeLeader() { return isThemeLeader; }
    public void setIsThemeLeader(Boolean themeLeader) { isThemeLeader = themeLeader; }
    public Boolean getLeadershipOnly() { return leadershipOnly; }
    public void setLeadershipOnly(Boolean leadershipOnly) { this.leadershipOnly = leadershipOnly; }
    public String getThemeLeaderSymbol() { return themeLeaderSymbol; }
    public void setThemeLeaderSymbol(String themeLeaderSymbol) { this.themeLeaderSymbol = themeLeaderSymbol; }
    public Boolean getResearchUniverse() { return researchUniverse; }
    public void setResearchUniverse(Boolean researchUniverse) { this.researchUniverse = researchUniverse; }
    public Boolean getTradableUniverse() { return tradableUniverse; }
    public void setTradableUniverse(Boolean tradableUniverse) { this.tradableUniverse = tradableUniverse; }
    public Boolean getLeaderTradable() { return leaderTradable; }
    public void setLeaderTradable(Boolean leaderTradable) { this.leaderTradable = leaderTradable; }
    public BigDecimal getThemeImportanceScore() { return themeImportanceScore; }
    public void setThemeImportanceScore(BigDecimal themeImportanceScore) { this.themeImportanceScore = themeImportanceScore; }
    public BigDecimal getTradableScore() { return tradableScore; }
    public void setTradableScore(BigDecimal tradableScore) { this.tradableScore = tradableScore; }
    public BigDecimal getShadowRankScore() { return shadowRankScore; }
    public void setShadowRankScore(BigDecimal shadowRankScore) { this.shadowRankScore = shadowRankScore; }
    public BigDecimal getDivergenceScore() { return divergenceScore; }
    public void setDivergenceScore(BigDecimal divergenceScore) { this.divergenceScore = divergenceScore; }
    public BigDecimal getTaxonomyGapScore() { return taxonomyGapScore; }
    public void setTaxonomyGapScore(BigDecimal taxonomyGapScore) { this.taxonomyGapScore = taxonomyGapScore; }
    public Boolean getRiskRejected() { return riskRejected; }
    public void setRiskRejected(Boolean riskRejected) { this.riskRejected = riskRejected; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getSafetyNote() { return safetyNote; }
    public void setSafetyNote(String safetyNote) { this.safetyNote = safetyNote; }
    public String getAiGovernanceSummary() { return aiGovernanceSummary; }
    public void setAiGovernanceSummary(String aiGovernanceSummary) { this.aiGovernanceSummary = aiGovernanceSummary; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
