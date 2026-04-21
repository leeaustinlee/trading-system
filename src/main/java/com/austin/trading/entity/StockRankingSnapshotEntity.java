package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted ranking snapshot for one candidate on one trading day.
 *
 * <p>Produced by {@link com.austin.trading.service.StockRankingService} and
 * consumed by the Setup layer (P0.3) to trace which candidates were eligible
 * for setup validation.</p>
 */
@Entity
@Table(
        name = "stock_ranking_snapshot",
        indexes = {
                @Index(name = "idx_ranking_date_score",  columnList = "trading_date, selection_score DESC"),
                @Index(name = "idx_ranking_date_symbol", columnList = "trading_date, symbol")
        }
)
public class StockRankingSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "selection_score", precision = 7, scale = 3)
    private BigDecimal selectionScore;

    @Column(name = "relative_strength_score", precision = 7, scale = 3)
    private BigDecimal relativeStrengthScore;

    @Column(name = "theme_strength_score", precision = 7, scale = 3)
    private BigDecimal themeStrengthScore;

    @Column(name = "thesis_score", precision = 7, scale = 3)
    private BigDecimal thesisScore;

    @Column(name = "theme_tag", length = 60)
    private String themeTag;

    @Column(name = "vetoed", nullable = false)
    private boolean vetoed;

    @Column(name = "eligible_for_setup", nullable = false)
    private boolean eligibleForSetup;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "score_breakdown_json", columnDefinition = "json")
    private String scoreBreakdownJson;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ─────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public BigDecimal getSelectionScore() { return selectionScore; }
    public void setSelectionScore(BigDecimal selectionScore) { this.selectionScore = selectionScore; }

    public BigDecimal getRelativeStrengthScore() { return relativeStrengthScore; }
    public void setRelativeStrengthScore(BigDecimal relativeStrengthScore) { this.relativeStrengthScore = relativeStrengthScore; }

    public BigDecimal getThemeStrengthScore() { return themeStrengthScore; }
    public void setThemeStrengthScore(BigDecimal themeStrengthScore) { this.themeStrengthScore = themeStrengthScore; }

    public BigDecimal getThesisScore() { return thesisScore; }
    public void setThesisScore(BigDecimal thesisScore) { this.thesisScore = thesisScore; }

    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }

    public boolean isVetoed() { return vetoed; }
    public void setVetoed(boolean vetoed) { this.vetoed = vetoed; }

    public boolean isEligibleForSetup() { return eligibleForSetup; }
    public void setEligibleForSetup(boolean eligibleForSetup) { this.eligibleForSetup = eligibleForSetup; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getScoreBreakdownJson() { return scoreBreakdownJson; }
    public void setScoreBreakdownJson(String scoreBreakdownJson) { this.scoreBreakdownJson = scoreBreakdownJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
