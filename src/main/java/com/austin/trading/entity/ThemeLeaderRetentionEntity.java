package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Shadow-only retained market/theme leader from POSTMARKET super_strong_5.
 *
 * Safety contract: retained leaders are allowed into Claude research context for
 * market leadership / theme validation / peer discovery only. They must not feed
 * FinalDecisionEngine, BUY/SELL/ENTER, or production candidate ranking.
 */
@Entity
@Table(name = "theme_leader_retention",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "target_phase", "symbol"}),
        indexes = {
                @Index(name = "idx_theme_leader_retention_target", columnList = "target_phase, active, trading_date, leader_rank"),
                @Index(name = "idx_theme_leader_retention_symbol", columnList = "symbol, trading_date")
        })
public class ThemeLeaderRetentionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "source_phase", nullable = false, length = 32)
    private String sourcePhase;

    @Column(name = "target_phase", nullable = false, length = 32)
    private String targetPhase;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "leader_rank")
    private Integer leaderRank;

    @Column(name = "score", precision = 8, scale = 4)
    private BigDecimal score;

    @Column(name = "leader_tradable", nullable = false)
    private Boolean leaderTradable = false;

    @Column(name = "retention_reason", nullable = false, length = 500)
    private String retentionReason;

    @Column(name = "use_for", nullable = false, length = 255)
    private String useFor;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }
    public String getTargetPhase() { return targetPhase; }
    public void setTargetPhase(String targetPhase) { this.targetPhase = targetPhase; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public Integer getLeaderRank() { return leaderRank; }
    public void setLeaderRank(Integer leaderRank) { this.leaderRank = leaderRank; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public Boolean getLeaderTradable() { return leaderTradable; }
    public void setLeaderTradable(Boolean leaderTradable) { this.leaderTradable = leaderTradable; }
    public String getRetentionReason() { return retentionReason; }
    public void setRetentionReason(String retentionReason) { this.retentionReason = retentionReason; }
    public String getUseFor() { return useFor; }
    public void setUseFor(String useFor) { this.useFor = useFor; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
