package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_evaluation")
public class StockEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_profile", length = 40)
    private String stockProfile;

    @Column(name = "valuation_mode", length = 40)
    private String valuationMode;

    @Column(name = "entry_price_zone", length = 100)
    private String entryPriceZone;

    @Column(name = "stop_loss_price", precision = 12, scale = 4)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit_1", precision = 12, scale = 4)
    private BigDecimal takeProfit1;

    @Column(name = "take_profit_2", precision = 12, scale = 4)
    private BigDecimal takeProfit2;

    @Column(name = "risk_reward_ratio", precision = 8, scale = 4)
    private BigDecimal riskRewardRatio;

    @Column(name = "include_in_final_plan")
    private Boolean includeInFinalPlan;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockProfile() { return stockProfile; }
    public void setStockProfile(String stockProfile) { this.stockProfile = stockProfile; }
    public String getValuationMode() { return valuationMode; }
    public void setValuationMode(String valuationMode) { this.valuationMode = valuationMode; }
    public String getEntryPriceZone() { return entryPriceZone; }
    public void setEntryPriceZone(String entryPriceZone) { this.entryPriceZone = entryPriceZone; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; }
    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }
    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }
    public BigDecimal getRiskRewardRatio() { return riskRewardRatio; }
    public void setRiskRewardRatio(BigDecimal riskRewardRatio) { this.riskRewardRatio = riskRewardRatio; }
    public Boolean getIncludeInFinalPlan() { return includeInFinalPlan; }
    public void setIncludeInFinalPlan(Boolean includeInFinalPlan) { this.includeInFinalPlan = includeInFinalPlan; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
