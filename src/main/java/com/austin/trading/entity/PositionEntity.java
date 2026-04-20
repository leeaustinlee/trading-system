package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "position")
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "side", length = 10)
    private String side;

    @Column(name = "qty", precision = 12, scale = 4)
    private BigDecimal qty;

    @Column(name = "avg_cost", precision = 12, scale = 4)
    private BigDecimal avgCost;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "stop_loss_price", precision = 12, scale = 4)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit1", precision = 12, scale = 4)
    private BigDecimal takeProfit1;

    @Column(name = "take_profit2", precision = 12, scale = 4)
    private BigDecimal takeProfit2;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_price", precision = 12, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    @Column(name = "realized_pnl", precision = 14, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "trailing_stop_price", precision = 12, scale = 4)
    private BigDecimal trailingStopPrice;

    @Column(name = "review_status", length = 20)
    private String reviewStatus;

    /** v2.3 策略類型：SETUP / MOMENTUM_CHASE。影響出場邏輯。舊資料 default=SETUP。 */
    @Column(name = "strategy_type", length = 20, nullable = false)
    private String strategyType = "SETUP";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getAvgCost() { return avgCost; }
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; }
    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }
    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(LocalDateTime lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }
    public BigDecimal getTrailingStopPrice() { return trailingStopPrice; }
    public void setTrailingStopPrice(BigDecimal trailingStopPrice) { this.trailingStopPrice = trailingStopPrice; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getStrategyType() { return strategyType == null ? "SETUP" : strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
