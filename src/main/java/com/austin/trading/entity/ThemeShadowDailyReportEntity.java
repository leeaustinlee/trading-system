package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v2 Theme Engine Shadow Mode：每日彙總報表（對應 {@code theme-shadow-mode-spec.md §4.2}）。
 *
 * <p>PR1 僅建立欄位；PR5 ({@code ThemeShadowReportService}) 才會寫入。</p>
 */
@Entity
@Table(
        name = "theme_shadow_daily_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shadow_report_date",
                columnNames = "trading_date"
        )
)
public class ThemeShadowDailyReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "total_candidates", nullable = false)
    private Integer totalCandidates = 0;

    @Column(name = "same_buy_count", nullable = false)
    private Integer sameBuyCount = 0;

    @Column(name = "same_wait_count", nullable = false)
    private Integer sameWaitCount = 0;

    @Column(name = "legacy_buy_theme_block_count", nullable = false)
    private Integer legacyBuyThemeBlockCount = 0;

    @Column(name = "legacy_wait_theme_buy_count", nullable = false)
    private Integer legacyWaitThemeBuyCount = 0;

    @Column(name = "both_block_count", nullable = false)
    private Integer bothBlockCount = 0;

    @Column(name = "conflict_review_required_count", nullable = false)
    private Integer conflictReviewRequiredCount = 0;

    @Column(name = "avg_score_diff", precision = 6, scale = 3)
    private BigDecimal avgScoreDiff;

    @Column(name = "p90_abs_score_diff", precision = 6, scale = 3)
    private BigDecimal p90AbsScoreDiff;

    @Column(name = "top_conflicts_json", columnDefinition = "json")
    private String topConflictsJson;

    @Column(name = "report_markdown", columnDefinition = "TEXT")
    private String reportMarkdown;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── getters / setters ────────────────────────────────────────────

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate v) { this.tradingDate = v; }
    public Integer getTotalCandidates() { return totalCandidates; }
    public void setTotalCandidates(Integer v) { this.totalCandidates = v; }
    public Integer getSameBuyCount() { return sameBuyCount; }
    public void setSameBuyCount(Integer v) { this.sameBuyCount = v; }
    public Integer getSameWaitCount() { return sameWaitCount; }
    public void setSameWaitCount(Integer v) { this.sameWaitCount = v; }
    public Integer getLegacyBuyThemeBlockCount() { return legacyBuyThemeBlockCount; }
    public void setLegacyBuyThemeBlockCount(Integer v) { this.legacyBuyThemeBlockCount = v; }
    public Integer getLegacyWaitThemeBuyCount() { return legacyWaitThemeBuyCount; }
    public void setLegacyWaitThemeBuyCount(Integer v) { this.legacyWaitThemeBuyCount = v; }
    public Integer getBothBlockCount() { return bothBlockCount; }
    public void setBothBlockCount(Integer v) { this.bothBlockCount = v; }
    public Integer getConflictReviewRequiredCount() { return conflictReviewRequiredCount; }
    public void setConflictReviewRequiredCount(Integer v) { this.conflictReviewRequiredCount = v; }
    public BigDecimal getAvgScoreDiff() { return avgScoreDiff; }
    public void setAvgScoreDiff(BigDecimal v) { this.avgScoreDiff = v; }
    public BigDecimal getP90AbsScoreDiff() { return p90AbsScoreDiff; }
    public void setP90AbsScoreDiff(BigDecimal v) { this.p90AbsScoreDiff = v; }
    public String getTopConflictsJson() { return topConflictsJson; }
    public void setTopConflictsJson(String v) { this.topConflictsJson = v; }
    public String getReportMarkdown() { return reportMarkdown; }
    public void setReportMarkdown(String v) { this.reportMarkdown = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
