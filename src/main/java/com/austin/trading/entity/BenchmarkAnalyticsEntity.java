package com.austin.trading.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "benchmark_analytics",
       indexes = @Index(name = "idx_benchmark_period", columnList = "start_date, end_date"))
public class BenchmarkAnalyticsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "strategy_avg_return", precision = 8, scale = 4)
    private BigDecimal strategyAvgReturn;

    @Column(name = "market_avg_gain", precision = 8, scale = 4)
    private BigDecimal marketAvgGain;

    @Column(name = "traded_theme_avg_gain", precision = 8, scale = 4)
    private BigDecimal tradedThemeAvgGain;

    @Column(name = "market_alpha", precision = 8, scale = 4)
    private BigDecimal marketAlpha;

    @Column(name = "theme_alpha", precision = 8, scale = 4)
    private BigDecimal themeAlpha;

    @Column(name = "market_verdict", length = 20)
    private String marketVerdict;

    @Column(name = "theme_verdict", length = 20)
    private String themeVerdict;

    @Column(name = "trade_count")
    private Integer tradeCount;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── getters/setters ────────────────────────────────────────────────────────

    public Long getId() { return id; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public BigDecimal getStrategyAvgReturn() { return strategyAvgReturn; }
    public void setStrategyAvgReturn(BigDecimal v) { this.strategyAvgReturn = v; }

    public BigDecimal getMarketAvgGain() { return marketAvgGain; }
    public void setMarketAvgGain(BigDecimal v) { this.marketAvgGain = v; }

    public BigDecimal getTradedThemeAvgGain() { return tradedThemeAvgGain; }
    public void setTradedThemeAvgGain(BigDecimal v) { this.tradedThemeAvgGain = v; }

    public BigDecimal getMarketAlpha() { return marketAlpha; }
    public void setMarketAlpha(BigDecimal v) { this.marketAlpha = v; }

    public BigDecimal getThemeAlpha() { return themeAlpha; }
    public void setThemeAlpha(BigDecimal v) { this.themeAlpha = v; }

    public String getMarketVerdict() { return marketVerdict; }
    public void setMarketVerdict(String v) { this.marketVerdict = v; }

    public String getThemeVerdict() { return themeVerdict; }
    public void setThemeVerdict(String v) { this.themeVerdict = v; }

    public Integer getTradeCount() { return tradeCount; }
    public void setTradeCount(Integer v) { this.tradeCount = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
