package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v2 Theme Engine Shadow Mode：單筆候選股的 legacy vs theme 雙路徑結果
 * （對應 {@code theme-shadow-mode-spec.md §4.1}）。
 *
 * <p>PR1 僅建立欄位與 unique key；無任何 service 會寫入此表（Phase 2 PR5 才接）。</p>
 *
 * <p>欄位命名精準對齊 spec：{@code legacy_final_score / theme_final_score / score_diff /
 * legacy_decision / theme_decision / theme_veto_reason / decision_diff_type}。
 * 特別注意：{@code legacy_final_score} 欄位永遠不可被後續 PR 刪除（Austin 規則 8）。</p>
 */
@Entity
@Table(
        name = "theme_shadow_decision_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shadow_date_symbol",
                columnNames = {"trading_date", "symbol"}
        ),
        indexes = {
                @Index(name = "idx_shadow_date_diff",
                        columnList = "trading_date, decision_diff_type")
        }
)
public class ThemeShadowDecisionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "market_regime", length = 30)
    private String marketRegime;

    @Column(name = "legacy_final_score", precision = 6, scale = 3)
    private BigDecimal legacyFinalScore;

    @Column(name = "theme_final_score", precision = 6, scale = 3)
    private BigDecimal themeFinalScore;

    @Column(name = "score_diff", precision = 6, scale = 3)
    private BigDecimal scoreDiff;

    @Column(name = "legacy_decision", nullable = false, length = 20)
    private String legacyDecision;

    @Column(name = "theme_decision", nullable = false, length = 20)
    private String themeDecision;

    @Column(name = "theme_veto_reason", length = 80)
    private String themeVetoReason;

    @Column(name = "decision_diff_type", nullable = false, length = 40)
    private String decisionDiffType;

    @Column(name = "legacy_trace_json", columnDefinition = "json")
    private String legacyTraceJson;

    @Column(name = "theme_trace_json", columnDefinition = "json")
    private String themeTraceJson;

    @Column(name = "generated_by", nullable = false, length = 40)
    private String generatedBy = "FinalDecisionService";

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    // ── getters / setters ────────────────────────────────────────────

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getMarketRegime() { return marketRegime; }
    public void setMarketRegime(String marketRegime) { this.marketRegime = marketRegime; }
    public BigDecimal getLegacyFinalScore() { return legacyFinalScore; }
    public void setLegacyFinalScore(BigDecimal v) { this.legacyFinalScore = v; }
    public BigDecimal getThemeFinalScore() { return themeFinalScore; }
    public void setThemeFinalScore(BigDecimal v) { this.themeFinalScore = v; }
    public BigDecimal getScoreDiff() { return scoreDiff; }
    public void setScoreDiff(BigDecimal v) { this.scoreDiff = v; }
    public String getLegacyDecision() { return legacyDecision; }
    public void setLegacyDecision(String v) { this.legacyDecision = v; }
    public String getThemeDecision() { return themeDecision; }
    public void setThemeDecision(String v) { this.themeDecision = v; }
    public String getThemeVetoReason() { return themeVetoReason; }
    public void setThemeVetoReason(String v) { this.themeVetoReason = v; }
    public String getDecisionDiffType() { return decisionDiffType; }
    public void setDecisionDiffType(String v) { this.decisionDiffType = v; }
    public String getLegacyTraceJson() { return legacyTraceJson; }
    public void setLegacyTraceJson(String v) { this.legacyTraceJson = v; }
    public String getThemeTraceJson() { return themeTraceJson; }
    public void setThemeTraceJson(String v) { this.themeTraceJson = v; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String v) { this.generatedBy = v; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime v) { this.generatedAt = v; }
}
