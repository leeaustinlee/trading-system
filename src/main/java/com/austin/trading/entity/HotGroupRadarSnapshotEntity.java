package com.austin.trading.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "hot_group_radar_snapshot",
        indexes = {
                @Index(name = "idx_hot_group_radar_date_phase", columnList = "trading_date, source_phase"),
                @Index(name = "idx_hot_group_radar_theme", columnList = "theme_tag, trading_date")
        })
public class HotGroupRadarSnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "source_phase", nullable = false, length = 40) private String sourcePhase;
    @Column(name = "theme_tag", nullable = false, length = 120) private String themeTag;
    @Column(name = "theme_category", length = 80) private String themeCategory;
    @Column(name = "hot_score", precision = 12, scale = 4) private BigDecimal hotScore;
    @Column(name = "leader_count", nullable = false) private Integer leaderCount = 0;
    @Column(name = "limit_up_count", nullable = false) private Integer limitUpCount = 0;
    @Column(name = "near_limit_count", nullable = false) private Integer nearLimitCount = 0;
    @Column(name = "up_stock_count", nullable = false) private Integer upStockCount = 0;
    @Column(name = "avg_change_pct", precision = 10, scale = 4) private BigDecimal avgChangePct;
    @Column(name = "total_turnover_yi", precision = 14, scale = 4) private BigDecimal totalTurnoverYi;
    @Column(name = "diffusion_score", precision = 10, scale = 4) private BigDecimal diffusionScore;
    @Column(name = "news_score", precision = 10, scale = 4) private BigDecimal newsScore = BigDecimal.ZERO;
    @Column(name = "price_hike_signal", nullable = false) private Boolean priceHikeSignal = false;
    @Column(name = "risk_level", length = 40) private String riskLevel;
    @Column(name = "evidence_json", columnDefinition = "json") private String evidenceJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getThemeCategory() { return themeCategory; }
    public void setThemeCategory(String themeCategory) { this.themeCategory = themeCategory; }
    public BigDecimal getHotScore() { return hotScore; }
    public void setHotScore(BigDecimal hotScore) { this.hotScore = hotScore; }
    public Integer getLeaderCount() { return leaderCount; }
    public void setLeaderCount(Integer leaderCount) { this.leaderCount = leaderCount; }
    public Integer getLimitUpCount() { return limitUpCount; }
    public void setLimitUpCount(Integer limitUpCount) { this.limitUpCount = limitUpCount; }
    public Integer getNearLimitCount() { return nearLimitCount; }
    public void setNearLimitCount(Integer nearLimitCount) { this.nearLimitCount = nearLimitCount; }
    public Integer getUpStockCount() { return upStockCount; }
    public void setUpStockCount(Integer upStockCount) { this.upStockCount = upStockCount; }
    public BigDecimal getAvgChangePct() { return avgChangePct; }
    public void setAvgChangePct(BigDecimal avgChangePct) { this.avgChangePct = avgChangePct; }
    public BigDecimal getTotalTurnoverYi() { return totalTurnoverYi; }
    public void setTotalTurnoverYi(BigDecimal totalTurnoverYi) { this.totalTurnoverYi = totalTurnoverYi; }
    public BigDecimal getDiffusionScore() { return diffusionScore; }
    public void setDiffusionScore(BigDecimal diffusionScore) { this.diffusionScore = diffusionScore; }
    public BigDecimal getNewsScore() { return newsScore; }
    public void setNewsScore(BigDecimal newsScore) { this.newsScore = newsScore; }
    public Boolean getPriceHikeSignal() { return priceHikeSignal; }
    public void setPriceHikeSignal(Boolean priceHikeSignal) { this.priceHikeSignal = priceHikeSignal; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
