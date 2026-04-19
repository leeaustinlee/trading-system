package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "backtest_trade",
        indexes = {
                @Index(name = "idx_bt_trade_run", columnList = "backtest_run_id"),
                @Index(name = "idx_bt_trade_symbol", columnList = "symbol")
        })
public class BacktestTradeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backtest_run_id", nullable = false)
    private Long backtestRunId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "entry_price", precision = 12, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 12, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "pnl_pct", precision = 8, scale = 4)
    private BigDecimal pnlPct;

    @Column(name = "holding_days")
    private Integer holdingDays;

    @Column(name = "mfe_pct", precision = 8, scale = 4)
    private BigDecimal mfePct;

    @Column(name = "mae_pct", precision = 8, scale = 4)
    private BigDecimal maePct;

    @Column(name = "entry_trigger_type", length = 30)
    private String entryTriggerType;

    @Column(name = "entry_reason", length = 500)
    private String entryReason;

    @Column(name = "exit_reason", length = 500)
    private String exitReason;

    @Column(name = "score_snapshot_json", columnDefinition = "json")
    private String scoreSnapshotJson;

    @Column(name = "theme_snapshot_json", columnDefinition = "json")
    private String themeSnapshotJson;

    @Column(name = "position_snapshot_json", columnDefinition = "json")
    private String positionSnapshotJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── getters / setters ──
    public Long getId() { return id; }
    public Long getBacktestRunId() { return backtestRunId; }
    public void setBacktestRunId(Long v) { this.backtestRunId = v; }
    public Long getPositionId() { return positionId; }
    public void setPositionId(Long v) { this.positionId = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getStockName() { return stockName; }
    public void setStockName(String v) { this.stockName = v; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String v) { this.themeTag = v; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate v) { this.entryDate = v; }
    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate v) { this.exitDate = v; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal v) { this.entryPrice = v; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal v) { this.exitPrice = v; }
    public BigDecimal getPnlPct() { return pnlPct; }
    public void setPnlPct(BigDecimal v) { this.pnlPct = v; }
    public Integer getHoldingDays() { return holdingDays; }
    public void setHoldingDays(Integer v) { this.holdingDays = v; }
    public BigDecimal getMfePct() { return mfePct; }
    public void setMfePct(BigDecimal v) { this.mfePct = v; }
    public BigDecimal getMaePct() { return maePct; }
    public void setMaePct(BigDecimal v) { this.maePct = v; }
    public String getEntryTriggerType() { return entryTriggerType; }
    public void setEntryTriggerType(String v) { this.entryTriggerType = v; }
    public String getEntryReason() { return entryReason; }
    public void setEntryReason(String v) { this.entryReason = v; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String v) { this.exitReason = v; }
    public String getScoreSnapshotJson() { return scoreSnapshotJson; }
    public void setScoreSnapshotJson(String v) { this.scoreSnapshotJson = v; }
    public String getThemeSnapshotJson() { return themeSnapshotJson; }
    public void setThemeSnapshotJson(String v) { this.themeSnapshotJson = v; }
    public String getPositionSnapshotJson() { return positionSnapshotJson; }
    public void setPositionSnapshotJson(String v) { this.positionSnapshotJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
