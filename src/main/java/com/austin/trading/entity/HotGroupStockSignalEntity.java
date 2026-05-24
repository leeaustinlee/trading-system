package com.austin.trading.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "hot_group_stock_signal",
        indexes = {
                @Index(name = "idx_hot_group_signal_date_phase", columnList = "trading_date, source_phase"),
                @Index(name = "idx_hot_group_signal_symbol_date", columnList = "symbol, trading_date"),
                @Index(name = "idx_hot_group_signal_theme_date", columnList = "theme_tag, trading_date")
        })
public class HotGroupStockSignalEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "source_phase", nullable = false, length = 40) private String sourcePhase;
    @Column(name = "theme_tag", nullable = false, length = 120) private String themeTag;
    @Column(name = "symbol", nullable = false, length = 20) private String symbol;
    @Column(name = "stock_name", length = 120) private String stockName;
    @Column(name = "role", nullable = false, length = 40) private String role;
    @Column(name = "change_pct", precision = 10, scale = 4) private BigDecimal changePct;
    @Column(name = "turnover_yi", precision = 14, scale = 4) private BigDecimal turnoverYi;
    @Column(name = "near_high", precision = 10, scale = 4) private BigDecimal nearHigh;
    @Column(name = "limit_risk", nullable = false) private Boolean limitRisk = false;
    @Column(name = "board_lot_cost", precision = 14, scale = 4) private BigDecimal boardLotCost;
    @Column(name = "tradability_tag", length = 60) private String tradabilityTag;
    @Column(name = "radar_rank_score", precision = 12, scale = 4) private BigDecimal radarRankScore;
    @Column(name = "candidate_action", length = 60) private String candidateAction;
    @Column(name = "rejection_reason", length = 500) private String rejectionReason;
    @Column(name = "evidence_json", columnDefinition = "json") private String evidenceJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }
    public BigDecimal getTurnoverYi() { return turnoverYi; }
    public void setTurnoverYi(BigDecimal turnoverYi) { this.turnoverYi = turnoverYi; }
    public BigDecimal getNearHigh() { return nearHigh; }
    public void setNearHigh(BigDecimal nearHigh) { this.nearHigh = nearHigh; }
    public Boolean getLimitRisk() { return limitRisk; }
    public void setLimitRisk(Boolean limitRisk) { this.limitRisk = limitRisk; }
    public BigDecimal getBoardLotCost() { return boardLotCost; }
    public void setBoardLotCost(BigDecimal boardLotCost) { this.boardLotCost = boardLotCost; }
    public String getTradabilityTag() { return tradabilityTag; }
    public void setTradabilityTag(String tradabilityTag) { this.tradabilityTag = tradabilityTag; }
    public BigDecimal getRadarRankScore() { return radarRankScore; }
    public void setRadarRankScore(BigDecimal radarRankScore) { this.radarRankScore = radarRankScore; }
    public String getCandidateAction() { return candidateAction; }
    public void setCandidateAction(String candidateAction) { this.candidateAction = candidateAction; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
