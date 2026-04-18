package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "position_review_log",
        indexes = {
                @Index(name = "idx_review_position", columnList = "position_id"),
                @Index(name = "idx_review_date", columnList = "review_date")
        })
public class PositionReviewLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "review_date", nullable = false)
    private LocalDate reviewDate;

    @Column(name = "review_time")
    private LocalTime reviewTime;

    @Column(name = "review_type", nullable = false, length = 20)
    private String reviewType;

    @Column(name = "decision_status", nullable = false, length = 20)
    private String decisionStatus;

    @Column(name = "current_price", precision = 12, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "entry_price", precision = 12, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "pnl_pct", precision = 8, scale = 4)
    private BigDecimal pnlPct;

    @Column(name = "prev_stop_loss", precision = 12, scale = 4)
    private BigDecimal prevStopLoss;

    @Column(name = "suggested_stop", precision = 12, scale = 4)
    private BigDecimal suggestedStop;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "notified", nullable = false)
    private boolean notified = false;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── getters / setters ──────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate reviewDate) { this.reviewDate = reviewDate; }
    public LocalTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewType() { return reviewType; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }
    public String getDecisionStatus() { return decisionStatus; }
    public void setDecisionStatus(String decisionStatus) { this.decisionStatus = decisionStatus; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getPnlPct() { return pnlPct; }
    public void setPnlPct(BigDecimal pnlPct) { this.pnlPct = pnlPct; }
    public BigDecimal getPrevStopLoss() { return prevStopLoss; }
    public void setPrevStopLoss(BigDecimal prevStopLoss) { this.prevStopLoss = prevStopLoss; }
    public BigDecimal getSuggestedStop() { return suggestedStop; }
    public void setSuggestedStop(BigDecimal suggestedStop) { this.suggestedStop = suggestedStop; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public boolean isNotified() { return notified; }
    public void setNotified(boolean notified) { this.notified = notified; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
