package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Formal research universe item.
 *
 * Safety contract: rows in this table are research/shadow governance context only.
 * They must not feed FinalDecisionEngine, BUY/SELL/ENTER, production ranking,
 * allowed_symbols expansion, candidate gates, or risk-gate relaxation.
 */
@Entity
@Table(name = "research_universe_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "symbol", "theme_tag", "source"}),
        indexes = {
                @Index(name = "idx_research_universe_date_theme", columnList = "trading_date, theme_tag"),
                @Index(name = "idx_research_universe_date_symbol", columnList = "trading_date, symbol"),
                @Index(name = "idx_research_universe_governance", columnList = "trading_date, governance_status"),
                @Index(name = "idx_research_universe_role", columnList = "trading_date, research_role")
        })
public class ResearchUniverseItemEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "symbol", nullable = false, length = 20) private String symbol;
    @Column(name = "stock_name", length = 120) private String stockName;
    @Column(name = "theme_tag", nullable = false, length = 100) private String themeTag;
    @Column(name = "research_role", nullable = false, length = 40) private String researchRole;
    @Column(name = "source", nullable = false, length = 40) private String source;
    @Column(name = "research_score", precision = 8, scale = 4) private BigDecimal researchScore;
    @Column(name = "theme_importance_score", precision = 8, scale = 4) private BigDecimal themeImportanceScore;
    @Column(name = "tradable_score", precision = 8, scale = 4) private BigDecimal tradableScore;
    @Column(name = "narrative_density_score", precision = 8, scale = 4) private BigDecimal narrativeDensityScore;
    @Column(name = "governance_status", nullable = false, length = 50) private String governanceStatus = "SHADOW_ONLY";
    @Column(name = "research_universe", nullable = false) private Boolean researchUniverse = true;
    @Column(name = "tradable_universe", nullable = false) private Boolean tradableUniverse = false;
    @Column(name = "promoted_to_tradable", nullable = false) private Boolean promotedToTradable = false;
    @Column(name = "promotion_reason", length = 500) private String promotionReason;
    @Column(name = "blocked_reason", length = 500) private String blockedReason;
    @Column(name = "candidate_role", length = 40) private String candidateRole;
    @Column(name = "theme_leader_symbol", length = 20) private String themeLeaderSymbol;
    @Column(name = "leadership_only", nullable = false) private Boolean leadershipOnly = false;
    @Column(name = "leader_tradable", nullable = false) private Boolean leaderTradable = false;
    @Column(name = "safety_note", length = 500) private String safetyNote;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getResearchRole() { return researchRole; }
    public void setResearchRole(String researchRole) { this.researchRole = researchRole; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public BigDecimal getResearchScore() { return researchScore; }
    public void setResearchScore(BigDecimal researchScore) { this.researchScore = researchScore; }
    public BigDecimal getThemeImportanceScore() { return themeImportanceScore; }
    public void setThemeImportanceScore(BigDecimal themeImportanceScore) { this.themeImportanceScore = themeImportanceScore; }
    public BigDecimal getTradableScore() { return tradableScore; }
    public void setTradableScore(BigDecimal tradableScore) { this.tradableScore = tradableScore; }
    public BigDecimal getNarrativeDensityScore() { return narrativeDensityScore; }
    public void setNarrativeDensityScore(BigDecimal narrativeDensityScore) { this.narrativeDensityScore = narrativeDensityScore; }
    public String getGovernanceStatus() { return governanceStatus; }
    public void setGovernanceStatus(String governanceStatus) { this.governanceStatus = governanceStatus; }
    public Boolean getResearchUniverse() { return researchUniverse; }
    public void setResearchUniverse(Boolean researchUniverse) { this.researchUniverse = researchUniverse; }
    public Boolean getTradableUniverse() { return tradableUniverse; }
    public void setTradableUniverse(Boolean tradableUniverse) { this.tradableUniverse = tradableUniverse; }
    public Boolean getPromotedToTradable() { return promotedToTradable; }
    public void setPromotedToTradable(Boolean promotedToTradable) { this.promotedToTradable = promotedToTradable; }
    public String getPromotionReason() { return promotionReason; }
    public void setPromotionReason(String promotionReason) { this.promotionReason = promotionReason; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public String getCandidateRole() { return candidateRole; }
    public void setCandidateRole(String candidateRole) { this.candidateRole = candidateRole; }
    public String getThemeLeaderSymbol() { return themeLeaderSymbol; }
    public void setThemeLeaderSymbol(String themeLeaderSymbol) { this.themeLeaderSymbol = themeLeaderSymbol; }
    public Boolean getLeadershipOnly() { return leadershipOnly; }
    public void setLeadershipOnly(Boolean leadershipOnly) { this.leadershipOnly = leadershipOnly; }
    public Boolean getLeaderTradable() { return leaderTradable; }
    public void setLeaderTradable(Boolean leaderTradable) { this.leaderTradable = leaderTradable; }
    public String getSafetyNote() { return safetyNote; }
    public void setSafetyNote(String safetyNote) { this.safetyNote = safetyNote; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
