package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 題材快照 — 每個交易日每個題材一筆。
 * <p>
 * market_behavior_score：Java 由成交量、漲幅、強勢股數計算。<br>
 * theme_heat_score / theme_continuation_score：Claude 語意評估後回填。<br>
 * final_theme_score：Java 加權合併後的最終題材分數，用於候選股篩選排序。
 * </p>
 */
@Entity
@Table(name = "theme_snapshot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag"}))
public class ThemeSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "theme_tag", nullable = false, length = 100)
    private String themeTag;

    @Column(name = "theme_category", length = 50)
    private String themeCategory;

    // ── Java 計算 ──────────────────────────────────────────────
    @Column(name = "market_behavior_score", precision = 6, scale = 3)
    private BigDecimal marketBehaviorScore;

    @Column(name = "total_turnover", precision = 18, scale = 2)
    private BigDecimal totalTurnover;

    @Column(name = "avg_gain_pct", precision = 8, scale = 4)
    private BigDecimal avgGainPct;

    @Column(name = "strong_stock_count")
    private Integer strongStockCount;

    @Column(name = "leading_stock_symbol", length = 20)
    private String leadingStockSymbol;

    // ── Claude 語意回填 ──────────────────────────────────────────
    @Column(name = "theme_heat_score", precision = 6, scale = 3)
    private BigDecimal themeHeatScore;

    @Column(name = "theme_continuation_score", precision = 6, scale = 3)
    private BigDecimal themeContinuationScore;

    /** 法說 / 政策 / 報價 / 事件 / 籌碼 */
    @Column(name = "driver_type", length = 50)
    private String driverType;

    @Column(name = "risk_summary", length = 500)
    private String riskSummary;

    // ── 最終 ──────────────────────────────────────────────────
    @Column(name = "final_theme_score", precision = 6, scale = 3)
    private BigDecimal finalThemeScore;

    @Column(name = "ranking_order")
    private Integer rankingOrder;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getThemeCategory() { return themeCategory; }
    public void setThemeCategory(String themeCategory) { this.themeCategory = themeCategory; }
    public BigDecimal getMarketBehaviorScore() { return marketBehaviorScore; }
    public void setMarketBehaviorScore(BigDecimal marketBehaviorScore) { this.marketBehaviorScore = marketBehaviorScore; }
    public BigDecimal getTotalTurnover() { return totalTurnover; }
    public void setTotalTurnover(BigDecimal totalTurnover) { this.totalTurnover = totalTurnover; }
    public BigDecimal getAvgGainPct() { return avgGainPct; }
    public void setAvgGainPct(BigDecimal avgGainPct) { this.avgGainPct = avgGainPct; }
    public Integer getStrongStockCount() { return strongStockCount; }
    public void setStrongStockCount(Integer strongStockCount) { this.strongStockCount = strongStockCount; }
    public String getLeadingStockSymbol() { return leadingStockSymbol; }
    public void setLeadingStockSymbol(String leadingStockSymbol) { this.leadingStockSymbol = leadingStockSymbol; }
    public BigDecimal getThemeHeatScore() { return themeHeatScore; }
    public void setThemeHeatScore(BigDecimal themeHeatScore) { this.themeHeatScore = themeHeatScore; }
    public BigDecimal getThemeContinuationScore() { return themeContinuationScore; }
    public void setThemeContinuationScore(BigDecimal themeContinuationScore) { this.themeContinuationScore = themeContinuationScore; }
    public String getDriverType() { return driverType; }
    public void setDriverType(String driverType) { this.driverType = driverType; }
    public String getRiskSummary() { return riskSummary; }
    public void setRiskSummary(String riskSummary) { this.riskSummary = riskSummary; }
    public BigDecimal getFinalThemeScore() { return finalThemeScore; }
    public void setFinalThemeScore(BigDecimal finalThemeScore) { this.finalThemeScore = finalThemeScore; }
    public Integer getRankingOrder() { return rankingOrder; }
    public void setRankingOrder(Integer rankingOrder) { this.rankingOrder = rankingOrder; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
