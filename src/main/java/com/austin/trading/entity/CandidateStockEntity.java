package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_stock")
public class CandidateStockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "score", precision = 8, scale = 4)
    private BigDecimal score;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
