package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-only observability snapshot for market/theme leaders.
 *
 * Safety contract: rows in this table are diagnostics only. They must not feed
 * BUY/SELL/ENTER or FinalDecisionEngine gates.
 */
@Entity
@Table(name = "theme_leadership_snapshot",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "source_phase", "symbol"}),
        indexes = {
                @Index(name = "idx_theme_leader_date_theme", columnList = "trading_date, theme_tag"),
                @Index(name = "idx_theme_leader_date_rank", columnList = "trading_date, leader_rank"),
                @Index(name = "idx_theme_leader_phase", columnList = "trading_date, source_phase")
        })
public class ThemeLeadershipSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "source_phase", nullable = false, length = 32)
    private String sourcePhase;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "theme_category", length = 80)
    private String themeCategory;

    @Column(name = "sub_theme", length = 100)
    private String subTheme;

    @Column(name = "leader_rank")
    private Integer leaderRank;

    @Column(name = "hot_stock_rank")
    private Integer hotStockRank;

    @Column(name = "super_strong_rank")
    private Integer superStrongRank;

    @Column(name = "price_change_pct", precision = 8, scale = 4)
    private BigDecimal priceChangePct;

    @Column(name = "turnover", precision = 18, scale = 2)
    private BigDecimal turnover;

    @Column(name = "score", precision = 8, scale = 4)
    private BigDecimal score;

    @Column(name = "close_near_high")
    private Boolean closeNearHigh;

    @Column(name = "tradable")
    private Boolean tradable;

    @Column(name = "tradable_reason", length = 500)
    private String tradableReason;

    @Column(name = "retention_reason", length = 500)
    private String retentionReason;

    @Column(name = "taxonomy_status", length = 50)
    private String taxonomyStatus;

    @Column(name = "divergence_flags_json", columnDefinition = "json")
    private String divergenceFlagsJson;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getThemeCategory() { return themeCategory; }
    public void setThemeCategory(String themeCategory) { this.themeCategory = themeCategory; }
    public String getSubTheme() { return subTheme; }
    public void setSubTheme(String subTheme) { this.subTheme = subTheme; }
    public Integer getLeaderRank() { return leaderRank; }
    public void setLeaderRank(Integer leaderRank) { this.leaderRank = leaderRank; }
    public Integer getHotStockRank() { return hotStockRank; }
    public void setHotStockRank(Integer hotStockRank) { this.hotStockRank = hotStockRank; }
    public Integer getSuperStrongRank() { return superStrongRank; }
    public void setSuperStrongRank(Integer superStrongRank) { this.superStrongRank = superStrongRank; }
    public BigDecimal getPriceChangePct() { return priceChangePct; }
    public void setPriceChangePct(BigDecimal priceChangePct) { this.priceChangePct = priceChangePct; }
    public BigDecimal getTurnover() { return turnover; }
    public void setTurnover(BigDecimal turnover) { this.turnover = turnover; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public Boolean getCloseNearHigh() { return closeNearHigh; }
    public void setCloseNearHigh(Boolean closeNearHigh) { this.closeNearHigh = closeNearHigh; }
    public Boolean getTradable() { return tradable; }
    public void setTradable(Boolean tradable) { this.tradable = tradable; }
    public String getTradableReason() { return tradableReason; }
    public void setTradableReason(String tradableReason) { this.tradableReason = tradableReason; }
    public String getRetentionReason() { return retentionReason; }
    public void setRetentionReason(String retentionReason) { this.retentionReason = retentionReason; }
    public String getTaxonomyStatus() { return taxonomyStatus; }
    public void setTaxonomyStatus(String taxonomyStatus) { this.taxonomyStatus = taxonomyStatus; }
    public String getDivergenceFlagsJson() { return divergenceFlagsJson; }
    public void setDivergenceFlagsJson(String divergenceFlagsJson) { this.divergenceFlagsJson = divergenceFlagsJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
