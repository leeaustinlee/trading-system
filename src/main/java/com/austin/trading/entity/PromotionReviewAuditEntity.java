package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "promotion_review_audit",
        indexes = {
                @Index(name = "idx_promotion_review_audit_item", columnList = "review_item_id"),
                @Index(name = "idx_promotion_review_audit_date_symbol", columnList = "trading_date, symbol")
        })
public class PromotionReviewAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "review_item_id") private Long reviewItemId;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "symbol", nullable = false, length = 20) private String symbol;
    @Column(name = "from_status", length = 60) private String fromStatus;
    @Column(name = "to_status", length = 60) private String toStatus;
    @Column(name = "action", nullable = false, length = 40) private String action;
    @Column(name = "actor", length = 120) private String actor;
    @Column(name = "reason", length = 1000) private String reason;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Long getReviewItemId() { return reviewItemId; }
    public void setReviewItemId(Long reviewItemId) { this.reviewItemId = reviewItemId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
