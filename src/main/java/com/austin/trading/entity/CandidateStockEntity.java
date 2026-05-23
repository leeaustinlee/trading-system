package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_stock")
public class CandidateStockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "score", precision = 8, scale = 4)
    private BigDecimal score;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "candidate_role", length = 40)
    private String candidateRole;

    @Column(name = "theme_importance_score", precision = 8, scale = 4)
    private BigDecimal themeImportanceScore;

    @Column(name = "tradable_score", precision = 8, scale = 4)
    private BigDecimal tradableScore;

    @Column(name = "shadow_rank_score", precision = 8, scale = 4)
    private BigDecimal shadowRankScore;

    @Column(name = "theme_leader_symbol", length = 20)
    private String themeLeaderSymbol;

    @Column(name = "is_theme_leader")
    private Boolean isThemeLeader;

    @Column(name = "leader_tradable")
    private Boolean leaderTradable;

    @Column(name = "leader_retention_reason", length = 500)
    private String leaderRetentionReason;

    @Column(name = "theme_trace_id", length = 80)
    private String themeTraceId;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    // v2.3 Momentum Chase
    @Column(name = "is_momentum_candidate", nullable = false)
    private boolean isMomentumCandidate = false;

    @Column(name = "momentum_flags_json", columnDefinition = "json")
    private String momentumFlagsJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public String getCandidateRole() { return candidateRole; }
    public void setCandidateRole(String candidateRole) { this.candidateRole = candidateRole; }
    public BigDecimal getThemeImportanceScore() { return themeImportanceScore; }
    public void setThemeImportanceScore(BigDecimal themeImportanceScore) { this.themeImportanceScore = themeImportanceScore; }
    public BigDecimal getTradableScore() { return tradableScore; }
    public void setTradableScore(BigDecimal tradableScore) { this.tradableScore = tradableScore; }
    public BigDecimal getShadowRankScore() { return shadowRankScore; }
    public void setShadowRankScore(BigDecimal shadowRankScore) { this.shadowRankScore = shadowRankScore; }
    public String getThemeLeaderSymbol() { return themeLeaderSymbol; }
    public void setThemeLeaderSymbol(String themeLeaderSymbol) { this.themeLeaderSymbol = themeLeaderSymbol; }
    public Boolean getIsThemeLeader() { return isThemeLeader; }
    public void setIsThemeLeader(Boolean themeLeader) { isThemeLeader = themeLeader; }
    public Boolean getLeaderTradable() { return leaderTradable; }
    public void setLeaderTradable(Boolean leaderTradable) { this.leaderTradable = leaderTradable; }
    public String getLeaderRetentionReason() { return leaderRetentionReason; }
    public void setLeaderRetentionReason(String leaderRetentionReason) { this.leaderRetentionReason = leaderRetentionReason; }
    public String getThemeTraceId() { return themeTraceId; }
    public void setThemeTraceId(String themeTraceId) { this.themeTraceId = themeTraceId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public boolean isMomentumCandidate() { return isMomentumCandidate; }
    public void setMomentumCandidate(boolean momentumCandidate) { this.isMomentumCandidate = momentumCandidate; }
    public String getMomentumFlagsJson() { return momentumFlagsJson; }
    public void setMomentumFlagsJson(String momentumFlagsJson) { this.momentumFlagsJson = momentumFlagsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
