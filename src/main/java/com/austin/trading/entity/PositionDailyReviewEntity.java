package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "position_daily_review")
public class PositionDailyReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "stock_id", nullable = false, length = 20)
    private String stockId;

    @Column(name = "strength", length = 20)
    private String strength;

    @Column(name = "risk", length = 20)
    private String risk;

    @Column(name = "hold_decision", length = 20)
    private String holdDecision;

    @Column(name = "suggested_stop", precision = 12, scale = 4)
    private BigDecimal suggestedStop;

    @Column(name = "suggested_take_profit", precision = 12, scale = 4)
    private BigDecimal suggestedTakeProfit;

    @Column(name = "switch_flag", length = 20)
    private String switchFlag;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getStockId() { return stockId; }
    public void setStockId(String stockId) { this.stockId = stockId; }
    public String getStrength() { return strength; }
    public void setStrength(String strength) { this.strength = strength; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public String getHoldDecision() { return holdDecision; }
    public void setHoldDecision(String holdDecision) { this.holdDecision = holdDecision; }
    public BigDecimal getSuggestedStop() { return suggestedStop; }
    public void setSuggestedStop(BigDecimal suggestedStop) { this.suggestedStop = suggestedStop; }
    public BigDecimal getSuggestedTakeProfit() { return suggestedTakeProfit; }
    public void setSuggestedTakeProfit(BigDecimal suggestedTakeProfit) { this.suggestedTakeProfit = suggestedTakeProfit; }
    public String getSwitchFlag() { return switchFlag; }
    public void setSwitchFlag(String switchFlag) { this.switchFlag = switchFlag; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
