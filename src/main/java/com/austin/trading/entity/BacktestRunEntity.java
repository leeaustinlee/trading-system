package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "backtest_run",
        indexes = {
                @Index(name = "idx_bt_run_dates", columnList = "start_date, end_date"),
                @Index(name = "idx_bt_run_status", columnList = "status")
        })
public class BacktestRunEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_name", length = 200)
    private String runName;

    @Column(name = "run_type", nullable = false, length = 30)
    private String runType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "config_version", length = 30)
    private String configVersion;

    @Column(name = "config_snapshot_json", columnDefinition = "json")
    private String configSnapshotJson;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "win_count")
    private Integer winCount;

    @Column(name = "loss_count")
    private Integer lossCount;

    @Column(name = "win_rate", precision = 8, scale = 4)
    private BigDecimal winRate;

    @Column(name = "avg_return_pct", precision = 8, scale = 4)
    private BigDecimal avgReturnPct;

    @Column(name = "avg_holding_days", precision = 8, scale = 2)
    private BigDecimal avgHoldingDays;

    @Column(name = "max_drawdown_pct", precision = 8, scale = 4)
    private BigDecimal maxDrawdownPct;

    @Column(name = "profit_factor", precision = 8, scale = 4)
    private BigDecimal profitFactor;

    @Column(name = "best_trade_pct", precision = 8, scale = 4)
    private BigDecimal bestTradePct;

    @Column(name = "worst_trade_pct", precision = 8, scale = 4)
    private BigDecimal worstTradePct;

    @Column(name = "total_pnl", precision = 14, scale = 2)
    private BigDecimal totalPnl;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "RUNNING";

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // ── getters / setters ──
    public Long getId() { return id; }
    public String getRunName() { return runName; }
    public void setRunName(String v) { this.runName = v; }
    public String getRunType() { return runType; }
    public void setRunType(String v) { this.runType = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getConfigVersion() { return configVersion; }
    public void setConfigVersion(String v) { this.configVersion = v; }
    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public void setConfigSnapshotJson(String v) { this.configSnapshotJson = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Integer getTotalTrades() { return totalTrades; }
    public void setTotalTrades(Integer v) { this.totalTrades = v; }
    public Integer getWinCount() { return winCount; }
    public void setWinCount(Integer v) { this.winCount = v; }
    public Integer getLossCount() { return lossCount; }
    public void setLossCount(Integer v) { this.lossCount = v; }
    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal v) { this.winRate = v; }
    public BigDecimal getAvgReturnPct() { return avgReturnPct; }
    public void setAvgReturnPct(BigDecimal v) { this.avgReturnPct = v; }
    public BigDecimal getAvgHoldingDays() { return avgHoldingDays; }
    public void setAvgHoldingDays(BigDecimal v) { this.avgHoldingDays = v; }
    public BigDecimal getMaxDrawdownPct() { return maxDrawdownPct; }
    public void setMaxDrawdownPct(BigDecimal v) { this.maxDrawdownPct = v; }
    public BigDecimal getProfitFactor() { return profitFactor; }
    public void setProfitFactor(BigDecimal v) { this.profitFactor = v; }
    public BigDecimal getBestTradePct() { return bestTradePct; }
    public void setBestTradePct(BigDecimal v) { this.bestTradePct = v; }
    public BigDecimal getWorstTradePct() { return worstTradePct; }
    public void setWorstTradePct(BigDecimal v) { this.worstTradePct = v; }
    public BigDecimal getTotalPnl() { return totalPnl; }
    public void setTotalPnl(BigDecimal v) { this.totalPnl = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }
}
