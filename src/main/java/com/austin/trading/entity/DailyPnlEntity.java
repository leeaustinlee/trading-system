package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_pnl")
public class DailyPnlEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    /** 毛損益（各筆 realizedPnl 累計）。同步寫入供舊 summary API 使用 */
    @Column(name = "realized_pnl", precision = 14, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 14, scale = 4)
    private BigDecimal unrealizedPnl;

    @Column(name = "win_rate", precision = 8, scale = 4)
    private BigDecimal winRate;

    // ── 新增欄位（ddl-auto: update 自動建立）──

    /** 當日各筆已實現損益加總（不含費稅） */
    @Column(name = "gross_pnl", precision = 14, scale = 2)
    private BigDecimal grossPnl;

    /** 手續費＋交易稅估算 */
    @Column(name = "estimated_fee_and_tax", precision = 14, scale = 2)
    private BigDecimal estimatedFeeAndTax;

    /** 淨損益 = grossPnl − estimatedFeeAndTax */
    @Column(name = "net_pnl", precision = 14, scale = 2)
    private BigDecimal netPnl;

    /** 當日交易筆數 */
    @Column(name = "trade_count")
    private Integer tradeCount;

    /** 獲利筆數 */
    @Column(name = "win_count")
    private Integer winCount;

    /** 虧損筆數 */
    @Column(name = "loss_count")
    private Integer lossCount;

    /** 備註（手動填入券商實際數字等） */
    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Getters / Setters ──

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public void setUnrealizedPnl(BigDecimal unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }
    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }
    public BigDecimal getGrossPnl() { return grossPnl; }
    public void setGrossPnl(BigDecimal grossPnl) { this.grossPnl = grossPnl; }
    public BigDecimal getEstimatedFeeAndTax() { return estimatedFeeAndTax; }
    public void setEstimatedFeeAndTax(BigDecimal estimatedFeeAndTax) { this.estimatedFeeAndTax = estimatedFeeAndTax; }
    public BigDecimal getNetPnl() { return netPnl; }
    public void setNetPnl(BigDecimal netPnl) { this.netPnl = netPnl; }
    public Integer getTradeCount() { return tradeCount; }
    public void setTradeCount(Integer tradeCount) { this.tradeCount = tradeCount; }
    public Integer getWinCount() { return winCount; }
    public void setWinCount(Integer winCount) { this.winCount = winCount; }
    public Integer getLossCount() { return lossCount; }
    public void setLossCount(Integer lossCount) { this.lossCount = lossCount; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
