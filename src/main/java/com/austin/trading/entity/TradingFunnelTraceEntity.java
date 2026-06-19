package com.austin.trading.entity;

import com.austin.trading.domain.enums.TradingFunnelBlockedStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P0 Trading Funnel Shadow Trace entity.
 *
 * <p>Read-only/shadow observability row for reconstructing how a symbol moved
 * through the trading funnel. This entity is intentionally not referenced by
 * production decision services and must not affect BUY/SELL/candidate/watchlist/risk behavior.</p>
 */
@Entity
@Table(name = "trading_funnel_trace",
        indexes = {
                @Index(name = "idx_trading_funnel_trace_date", columnList = "trading_date"),
                @Index(name = "idx_trading_funnel_trace_symbol_date", columnList = "symbol, trading_date"),
                @Index(name = "idx_trading_funnel_trace_theme_date", columnList = "theme_tag, trading_date"),
                @Index(name = "idx_trading_funnel_trace_blocked", columnList = "blocked_stage, trading_date"),
                @Index(name = "idx_trading_funnel_trace_status", columnList = "trace_status, trading_date")
        })
public class TradingFunnelTraceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;
    @Column(name = "stock_name", length = 120)
    private String stockName;
    @Column(name = "theme_tag", length = 120)
    private String themeTag;

    @Column(name = "signal_id")
    private Long signalId;
    @Column(name = "signal_source", length = 80)
    private String signalSource;
    @Column(name = "signal_role", length = 40)
    private String signalRole;
    @Column(name = "signal_strength", precision = 12, scale = 4)
    private BigDecimal signalStrength;
    @Column(name = "signal_change_pct", precision = 12, scale = 4)
    private BigDecimal signalChangePct;
    @Column(name = "signal_near_limit")
    private Boolean signalNearLimit;
    @Column(name = "signal_limit_risk", length = 80)
    private String signalLimitRisk;

    @Column(name = "candidate_status", length = 40)
    private String candidateStatus;
    @Column(name = "candidate_reason", length = 500)
    private String candidateReason;
    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "watchlist_status", length = 40)
    private String watchlistStatus;
    @Column(name = "watchlist_reason", length = 500)
    private String watchlistReason;
    @Column(name = "watchlist_id")
    private Long watchlistId;

    @Column(name = "ranking_status", length = 40)
    private String rankingStatus;
    @Column(name = "ranking_rank")
    private Integer rankingRank;
    @Column(name = "ranking_score", precision = 12, scale = 4)
    private BigDecimal rankingScore;
    @Column(name = "ranking_reason", length = 500)
    private String rankingReason;
    @Column(name = "ranking_snapshot_id")
    private Long rankingSnapshotId;

    @Column(name = "setup_status", length = 40)
    private String setupStatus;
    @Column(name = "setup_reason", length = 500)
    private String setupReason;
    @Column(name = "setup_decision_id")
    private Long setupDecisionId;

    @Column(name = "risk_status", length = 40)
    private String riskStatus;
    @Column(name = "risk_reason", length = 500)
    private String riskReason;
    @Column(name = "risk_decision_id")
    private Long riskDecisionId;

    @Column(name = "portfolio_status", length = 40)
    private String portfolioStatus;
    @Column(name = "portfolio_reason", length = 500)
    private String portfolioReason;
    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "buy_status", length = 40)
    private String buyStatus;
    @Column(name = "buy_reason", length = 500)
    private String buyReason;
    @Column(name = "buy_trade_id")
    private Long buyTradeId;
    @Column(name = "buy_trade_ref", length = 120)
    private String buyTradeRef;

    @Column(name = "exit_status", length = 40)
    private String exitStatus;
    @Column(name = "exit_reason", length = 500)
    private String exitReason;
    @Column(name = "exit_ref_id")
    private Long exitRefId;

    @Column(name = "final_outcome_1d", precision = 12, scale = 4)
    private BigDecimal finalOutcome1d;
    @Column(name = "final_outcome_5d", precision = 12, scale = 4)
    private BigDecimal finalOutcome5d;
    @Column(name = "final_outcome_10d", precision = 12, scale = 4)
    private BigDecimal finalOutcome10d;
    @Column(name = "max_drawdown_10d", precision = 12, scale = 4)
    private BigDecimal maxDrawdown10d;

    @Enumerated(EnumType.STRING)
    @Column(name = "blocked_stage", length = 40)
    private TradingFunnelBlockedStage blockedStage;
    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "trace_source", nullable = false, length = 40)
    private String traceSource = "SHADOW";
    @Column(name = "trace_status", nullable = false, length = 40)
    private String traceStatus = "ACTIVE";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) { createdAt = now; }
        if (updatedAt == null) { updatedAt = now; }
        if (traceSource == null) { traceSource = "SHADOW"; }
        if (traceStatus == null) { traceStatus = "ACTIVE"; }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public Long getSignalId() { return signalId; }
    public void setSignalId(Long signalId) { this.signalId = signalId; }
    public String getSignalSource() { return signalSource; }
    public void setSignalSource(String signalSource) { this.signalSource = signalSource; }
    public String getSignalRole() { return signalRole; }
    public void setSignalRole(String signalRole) { this.signalRole = signalRole; }
    public BigDecimal getSignalStrength() { return signalStrength; }
    public void setSignalStrength(BigDecimal signalStrength) { this.signalStrength = signalStrength; }
    public BigDecimal getSignalChangePct() { return signalChangePct; }
    public void setSignalChangePct(BigDecimal signalChangePct) { this.signalChangePct = signalChangePct; }
    public Boolean getSignalNearLimit() { return signalNearLimit; }
    public void setSignalNearLimit(Boolean signalNearLimit) { this.signalNearLimit = signalNearLimit; }
    public String getSignalLimitRisk() { return signalLimitRisk; }
    public void setSignalLimitRisk(String signalLimitRisk) { this.signalLimitRisk = signalLimitRisk; }
    public String getCandidateStatus() { return candidateStatus; }
    public void setCandidateStatus(String candidateStatus) { this.candidateStatus = candidateStatus; }
    public String getCandidateReason() { return candidateReason; }
    public void setCandidateReason(String candidateReason) { this.candidateReason = candidateReason; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public String getWatchlistStatus() { return watchlistStatus; }
    public void setWatchlistStatus(String watchlistStatus) { this.watchlistStatus = watchlistStatus; }
    public String getWatchlistReason() { return watchlistReason; }
    public void setWatchlistReason(String watchlistReason) { this.watchlistReason = watchlistReason; }
    public Long getWatchlistId() { return watchlistId; }
    public void setWatchlistId(Long watchlistId) { this.watchlistId = watchlistId; }
    public String getRankingStatus() { return rankingStatus; }
    public void setRankingStatus(String rankingStatus) { this.rankingStatus = rankingStatus; }
    public Integer getRankingRank() { return rankingRank; }
    public void setRankingRank(Integer rankingRank) { this.rankingRank = rankingRank; }
    public BigDecimal getRankingScore() { return rankingScore; }
    public void setRankingScore(BigDecimal rankingScore) { this.rankingScore = rankingScore; }
    public String getRankingReason() { return rankingReason; }
    public void setRankingReason(String rankingReason) { this.rankingReason = rankingReason; }
    public Long getRankingSnapshotId() { return rankingSnapshotId; }
    public void setRankingSnapshotId(Long rankingSnapshotId) { this.rankingSnapshotId = rankingSnapshotId; }
    public String getSetupStatus() { return setupStatus; }
    public void setSetupStatus(String setupStatus) { this.setupStatus = setupStatus; }
    public String getSetupReason() { return setupReason; }
    public void setSetupReason(String setupReason) { this.setupReason = setupReason; }
    public Long getSetupDecisionId() { return setupDecisionId; }
    public void setSetupDecisionId(Long setupDecisionId) { this.setupDecisionId = setupDecisionId; }
    public String getRiskStatus() { return riskStatus; }
    public void setRiskStatus(String riskStatus) { this.riskStatus = riskStatus; }
    public String getRiskReason() { return riskReason; }
    public void setRiskReason(String riskReason) { this.riskReason = riskReason; }
    public Long getRiskDecisionId() { return riskDecisionId; }
    public void setRiskDecisionId(Long riskDecisionId) { this.riskDecisionId = riskDecisionId; }
    public String getPortfolioStatus() { return portfolioStatus; }
    public void setPortfolioStatus(String portfolioStatus) { this.portfolioStatus = portfolioStatus; }
    public String getPortfolioReason() { return portfolioReason; }
    public void setPortfolioReason(String portfolioReason) { this.portfolioReason = portfolioReason; }
    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }
    public String getBuyStatus() { return buyStatus; }
    public void setBuyStatus(String buyStatus) { this.buyStatus = buyStatus; }
    public String getBuyReason() { return buyReason; }
    public void setBuyReason(String buyReason) { this.buyReason = buyReason; }
    public Long getBuyTradeId() { return buyTradeId; }
    public void setBuyTradeId(Long buyTradeId) { this.buyTradeId = buyTradeId; }
    public String getBuyTradeRef() { return buyTradeRef; }
    public void setBuyTradeRef(String buyTradeRef) { this.buyTradeRef = buyTradeRef; }
    public String getExitStatus() { return exitStatus; }
    public void setExitStatus(String exitStatus) { this.exitStatus = exitStatus; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public Long getExitRefId() { return exitRefId; }
    public void setExitRefId(Long exitRefId) { this.exitRefId = exitRefId; }
    public BigDecimal getFinalOutcome1d() { return finalOutcome1d; }
    public void setFinalOutcome1d(BigDecimal finalOutcome1d) { this.finalOutcome1d = finalOutcome1d; }
    public BigDecimal getFinalOutcome5d() { return finalOutcome5d; }
    public void setFinalOutcome5d(BigDecimal finalOutcome5d) { this.finalOutcome5d = finalOutcome5d; }
    public BigDecimal getFinalOutcome10d() { return finalOutcome10d; }
    public void setFinalOutcome10d(BigDecimal finalOutcome10d) { this.finalOutcome10d = finalOutcome10d; }
    public BigDecimal getMaxDrawdown10d() { return maxDrawdown10d; }
    public void setMaxDrawdown10d(BigDecimal maxDrawdown10d) { this.maxDrawdown10d = maxDrawdown10d; }
    public TradingFunnelBlockedStage getBlockedStage() { return blockedStage; }
    public void setBlockedStage(TradingFunnelBlockedStage blockedStage) { this.blockedStage = blockedStage; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public String getTraceSource() { return traceSource; }
    public void setTraceSource(String traceSource) { this.traceSource = traceSource; }
    public String getTraceStatus() { return traceStatus; }
    public void setTraceStatus(String traceStatus) { this.traceStatus = traceStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
