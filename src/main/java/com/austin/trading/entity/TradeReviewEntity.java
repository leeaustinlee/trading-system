package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_review",
        indexes = {
                @Index(name = "idx_tr_position", columnList = "position_id"),
                @Index(name = "idx_tr_symbol", columnList = "symbol"),
                @Index(name = "idx_tr_tag", columnList = "primary_tag")
        })
public class TradeReviewEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "entry_price", precision = 12, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 12, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "pnl_pct", precision = 8, scale = 4)
    private BigDecimal pnlPct;

    @Column(name = "holding_days")
    private Integer holdingDays;

    @Column(name = "mfe_pct", precision = 8, scale = 4)
    private BigDecimal mfePct;

    @Column(name = "mae_pct", precision = 8, scale = 4)
    private BigDecimal maePct;

    @Column(name = "market_condition", length = 10)
    private String marketCondition;

    @Column(name = "review_grade", nullable = false, length = 5)
    private String reviewGrade;

    @Column(name = "primary_tag", nullable = false, length = 50)
    private String primaryTag;

    @Column(name = "secondary_tags_json", columnDefinition = "json")
    private String secondaryTagsJson;

    @Column(name = "strengths_json", columnDefinition = "json")
    private String strengthsJson;

    @Column(name = "weaknesses_json", columnDefinition = "json")
    private String weaknessesJson;

    @Column(name = "improvement_suggestions_json", columnDefinition = "json")
    private String improvementSuggestionsJson;

    @Column(name = "ai_summary", columnDefinition = "text")
    private String aiSummary;

    @Column(name = "reviewer_type", nullable = false, length = 20)
    private String reviewerType = "RULE_ENGINE";

    @Column(name = "review_version", nullable = false)
    private int reviewVersion = 1;

    @Column(name = "score_snapshot_json", columnDefinition = "json")
    private String scoreSnapshotJson;

    @Column(name = "market_snapshot_json", columnDefinition = "json")
    private String marketSnapshotJson;

    @Column(name = "theme_snapshot_json", columnDefinition = "json")
    private String themeSnapshotJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── getters / setters ──
    public Long getId() { return id; }
    public Long getPositionId() { return positionId; }
    public void setPositionId(Long v) { this.positionId = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate v) { this.entryDate = v; }
    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate v) { this.exitDate = v; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal v) { this.entryPrice = v; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal v) { this.exitPrice = v; }
    public BigDecimal getPnlPct() { return pnlPct; }
    public void setPnlPct(BigDecimal v) { this.pnlPct = v; }
    public Integer getHoldingDays() { return holdingDays; }
    public void setHoldingDays(Integer v) { this.holdingDays = v; }
    public BigDecimal getMfePct() { return mfePct; }
    public void setMfePct(BigDecimal v) { this.mfePct = v; }
    public BigDecimal getMaePct() { return maePct; }
    public void setMaePct(BigDecimal v) { this.maePct = v; }
    public String getMarketCondition() { return marketCondition; }
    public void setMarketCondition(String v) { this.marketCondition = v; }
    public String getReviewGrade() { return reviewGrade; }
    public void setReviewGrade(String v) { this.reviewGrade = v; }
    public String getPrimaryTag() { return primaryTag; }
    public void setPrimaryTag(String v) { this.primaryTag = v; }
    public String getSecondaryTagsJson() { return secondaryTagsJson; }
    public void setSecondaryTagsJson(String v) { this.secondaryTagsJson = v; }
    public String getStrengthsJson() { return strengthsJson; }
    public void setStrengthsJson(String v) { this.strengthsJson = v; }
    public String getWeaknessesJson() { return weaknessesJson; }
    public void setWeaknessesJson(String v) { this.weaknessesJson = v; }
    public String getImprovementSuggestionsJson() { return improvementSuggestionsJson; }
    public void setImprovementSuggestionsJson(String v) { this.improvementSuggestionsJson = v; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String v) { this.aiSummary = v; }
    public String getReviewerType() { return reviewerType; }
    public void setReviewerType(String v) { this.reviewerType = v; }
    public int getReviewVersion() { return reviewVersion; }
    public void setReviewVersion(int v) { this.reviewVersion = v; }
    public String getScoreSnapshotJson() { return scoreSnapshotJson; }
    public void setScoreSnapshotJson(String v) { this.scoreSnapshotJson = v; }
    public String getMarketSnapshotJson() { return marketSnapshotJson; }
    public void setMarketSnapshotJson(String v) { this.marketSnapshotJson = v; }
    public String getThemeSnapshotJson() { return themeSnapshotJson; }
    public void setThemeSnapshotJson(String v) { this.themeSnapshotJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
