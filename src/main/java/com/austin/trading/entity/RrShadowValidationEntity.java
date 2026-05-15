package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "rr_shadow_validation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_rr_shadow_validation_paper_trade", columnNames = "paper_trade_id")
        },
        indexes = {
                @Index(name = "idx_rr_shadow_validation_date", columnList = "trading_date"),
                @Index(name = "idx_rr_shadow_validation_status", columnList = "shadow_status"),
                @Index(name = "idx_rr_shadow_validation_bucket", columnList = "root_cause_bucket")
        })
public class RrShadowValidationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "paper_trade_id")
    private Long paperTradeId;
    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Column(name = "strategy_type", length = 30)
    private String strategyType;
    @Column(name = "entry_price", precision = 12, scale = 4)
    private BigDecimal entryPrice;
    @Column(name = "stop_loss_price", precision = 12, scale = 4)
    private BigDecimal stopLossPrice;
    @Column(name = "target1_price", precision = 12, scale = 4)
    private BigDecimal target1Price;
    @Column(name = "target2_price", precision = 12, scale = 4)
    private BigDecimal target2Price;
    @Column(name = "rr_ratio", precision = 12, scale = 4)
    private BigDecimal rrRatio;
    @Column(name = "shadow_status", nullable = false, length = 20)
    private String shadowStatus;
    @Column(name = "root_cause_bucket", length = 60)
    private String rootCauseBucket;
    @Column(name = "t1_return_pct", precision = 12, scale = 4)
    private BigDecimal t1ReturnPct;
    @Column(name = "t3_return_pct", precision = 12, scale = 4)
    private BigDecimal t3ReturnPct;
    @Column(name = "t5_return_pct", precision = 12, scale = 4)
    private BigDecimal t5ReturnPct;
    @Column(name = "t10_return_pct", precision = 12, scale = 4)
    private BigDecimal t10ReturnPct;
    @Column(name = "avoided_loser_flag", nullable = false)
    private boolean avoidedLoserFlag;
    @Column(name = "missed_winner_flag", nullable = false)
    private boolean missedWinnerFlag;
    @Column(name = "data_gap_reason", length = 1000)
    private String dataGapReason;
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public Long getPaperTradeId() { return paperTradeId; }
    public void setPaperTradeId(Long paperTradeId) { this.paperTradeId = paperTradeId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; }
    public BigDecimal getTarget1Price() { return target1Price; }
    public void setTarget1Price(BigDecimal target1Price) { this.target1Price = target1Price; }
    public BigDecimal getTarget2Price() { return target2Price; }
    public void setTarget2Price(BigDecimal target2Price) { this.target2Price = target2Price; }
    public BigDecimal getRrRatio() { return rrRatio; }
    public void setRrRatio(BigDecimal rrRatio) { this.rrRatio = rrRatio; }
    public String getShadowStatus() { return shadowStatus; }
    public void setShadowStatus(String shadowStatus) { this.shadowStatus = shadowStatus; }
    public String getRootCauseBucket() { return rootCauseBucket; }
    public void setRootCauseBucket(String rootCauseBucket) { this.rootCauseBucket = rootCauseBucket; }
    public BigDecimal getT1ReturnPct() { return t1ReturnPct; }
    public void setT1ReturnPct(BigDecimal t1ReturnPct) { this.t1ReturnPct = t1ReturnPct; }
    public BigDecimal getT3ReturnPct() { return t3ReturnPct; }
    public void setT3ReturnPct(BigDecimal t3ReturnPct) { this.t3ReturnPct = t3ReturnPct; }
    public BigDecimal getT5ReturnPct() { return t5ReturnPct; }
    public void setT5ReturnPct(BigDecimal t5ReturnPct) { this.t5ReturnPct = t5ReturnPct; }
    public BigDecimal getT10ReturnPct() { return t10ReturnPct; }
    public void setT10ReturnPct(BigDecimal t10ReturnPct) { this.t10ReturnPct = t10ReturnPct; }
    public boolean isAvoidedLoserFlag() { return avoidedLoserFlag; }
    public void setAvoidedLoserFlag(boolean avoidedLoserFlag) { this.avoidedLoserFlag = avoidedLoserFlag; }
    public boolean isMissedWinnerFlag() { return missedWinnerFlag; }
    public void setMissedWinnerFlag(boolean missedWinnerFlag) { this.missedWinnerFlag = missedWinnerFlag; }
    public String getDataGapReason() { return dataGapReason; }
    public void setDataGapReason(String dataGapReason) { this.dataGapReason = dataGapReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
