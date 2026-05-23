package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Shadow-only peer universe derived from retained theme leaders.
 *
 * Safety contract: rows in this table are observability/replay context only.
 * They must not feed FinalDecisionEngine, BUY/SELL/ENTER, production ranking,
 * allowed_symbols expansion, or risk-gate relaxation.
 */
@Entity
@Table(name = "theme_peer_shadow_candidate",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "source_phase", "leader_symbol", "symbol"}),
        indexes = {
                @Index(name = "idx_theme_peer_shadow_date_phase", columnList = "trading_date, source_phase, shadow_rank_score"),
                @Index(name = "idx_theme_peer_shadow_leader", columnList = "leader_symbol, trading_date, shadow_rank_score"),
                @Index(name = "idx_theme_peer_shadow_symbol", columnList = "symbol, trading_date")
        })
public class ThemePeerShadowCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "source_phase", nullable = false, length = 32)
    private String sourcePhase;

    @Column(name = "leader_symbol", nullable = false, length = 20)
    private String leaderSymbol;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "candidate_role", nullable = false, length = 40)
    private String candidateRole;

    @Column(name = "theme_importance_score", precision = 8, scale = 4)
    private BigDecimal themeImportanceScore;

    @Column(name = "tradable_score", precision = 8, scale = 4)
    private BigDecimal tradableScore;

    @Column(name = "shadow_rank_score", precision = 8, scale = 4)
    private BigDecimal shadowRankScore;

    @Column(name = "tradable", nullable = false)
    private Boolean tradable = false;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "evidence_json", columnDefinition = "json")
    private String evidenceJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }
    public String getLeaderSymbol() { return leaderSymbol; }
    public void setLeaderSymbol(String leaderSymbol) { this.leaderSymbol = leaderSymbol; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getCandidateRole() { return candidateRole; }
    public void setCandidateRole(String candidateRole) { this.candidateRole = candidateRole; }
    public BigDecimal getThemeImportanceScore() { return themeImportanceScore; }
    public void setThemeImportanceScore(BigDecimal themeImportanceScore) { this.themeImportanceScore = themeImportanceScore; }
    public BigDecimal getTradableScore() { return tradableScore; }
    public void setTradableScore(BigDecimal tradableScore) { this.tradableScore = tradableScore; }
    public BigDecimal getShadowRankScore() { return shadowRankScore; }
    public void setShadowRankScore(BigDecimal shadowRankScore) { this.shadowRankScore = shadowRankScore; }
    public Boolean getTradable() { return tradable; }
    public void setTradable(Boolean tradable) { this.tradable = tradable; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
