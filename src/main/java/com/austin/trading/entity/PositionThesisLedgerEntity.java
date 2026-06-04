package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "position_thesis_ledger")
public class PositionThesisLedgerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "paper_trade_id")
    private Long paperTradeId;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "avg_cost", precision = 12, scale = 4)
    private BigDecimal avgCost;

    @Column(name = "entry_source", length = 40)
    private String entrySource;

    @Column(name = "entry_decision_id")
    private Long entryDecisionId;

    @Column(name = "primary_theme", length = 120)
    private String primaryTheme;

    @Column(name = "secondary_themes", columnDefinition = "json")
    private String secondaryThemes;

    @Column(name = "thesis_summary", columnDefinition = "text")
    private String thesisSummary;

    @Column(name = "theme_lifecycle", length = 40)
    private String themeLifecycle;

    @Column(name = "theme_heat", precision = 8, scale = 4)
    private BigDecimal themeHeat;

    @Column(name = "theme_breadth", precision = 8, scale = 4)
    private BigDecimal themeBreadth;

    @Column(name = "rotation_strength", precision = 8, scale = 4)
    private BigDecimal rotationStrength;

    @Column(name = "narrative_heat", precision = 8, scale = 4)
    private BigDecimal narrativeHeat;

    @Column(name = "crowding_risk", length = 20)
    private String crowdingRisk;

    @Column(name = "institutional_alignment", length = 30)
    private String institutionalAlignment;

    @Column(name = "wave_phase", length = 40)
    private String wavePhase;

    @Column(name = "market_context", columnDefinition = "json")
    private String marketContext;

    @Column(name = "sector_leadership", length = 30)
    private String sectorLeadership;

    @Column(name = "theme_still_active")
    private Boolean themeStillActive;

    @Column(name = "entry_reason", columnDefinition = "text")
    private String entryReason;

    @Column(name = "expected_holding_days")
    private Integer expectedHoldingDays;

    @Column(name = "invalidation_condition", columnDefinition = "text")
    private String invalidationCondition;

    @Column(name = "stop_type", length = 60)
    private String stopType;

    @Column(name = "target_wave", length = 120)
    private String targetWave;

    @Column(name = "thesis_status", nullable = false, length = 30)
    private String thesisStatus = "UNKNOWN";

    @Column(name = "thesis_confidence", precision = 5, scale = 2)
    private BigDecimal thesisConfidence;

    @Column(name = "latest_review_date")
    private LocalDateTime latestReviewDate;

    @Column(name = "latest_review_reason", columnDefinition = "text")
    private String latestReviewReason;

    @Column(name = "open_position", nullable = false)
    private boolean openPosition = true;

    @Column(name = "evidence_json", columnDefinition = "json")
    private String evidenceJson;

    @Column(name = "production_decision_allowed", nullable = false)
    private boolean productionDecisionAllowed = false;

    @Column(name = "auto_buy_enabled", nullable = false)
    private boolean autoBuyEnabled = false;

    @Column(name = "auto_sell_enabled", nullable = false)
    private boolean autoSellEnabled = false;

    @Column(name = "manual_confirm_required", nullable = false)
    private boolean manualConfirmRequired = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }
    public Long getPaperTradeId() { return paperTradeId; }
    public void setPaperTradeId(Long paperTradeId) { this.paperTradeId = paperTradeId; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }
    public BigDecimal getAvgCost() { return avgCost; }
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }
    public String getEntrySource() { return entrySource; }
    public void setEntrySource(String entrySource) { this.entrySource = entrySource; }
    public Long getEntryDecisionId() { return entryDecisionId; }
    public void setEntryDecisionId(Long entryDecisionId) { this.entryDecisionId = entryDecisionId; }
    public String getPrimaryTheme() { return primaryTheme; }
    public void setPrimaryTheme(String primaryTheme) { this.primaryTheme = primaryTheme; }
    public String getSecondaryThemes() { return secondaryThemes; }
    public void setSecondaryThemes(String secondaryThemes) { this.secondaryThemes = secondaryThemes; }
    public String getThesisSummary() { return thesisSummary; }
    public void setThesisSummary(String thesisSummary) { this.thesisSummary = thesisSummary; }

    public String getThemeLifecycle() { return themeLifecycle; }
    public void setThemeLifecycle(String themeLifecycle) { this.themeLifecycle = themeLifecycle; }
    public BigDecimal getThemeHeat() { return themeHeat; }
    public void setThemeHeat(BigDecimal themeHeat) { this.themeHeat = themeHeat; }
    public BigDecimal getThemeBreadth() { return themeBreadth; }
    public void setThemeBreadth(BigDecimal themeBreadth) { this.themeBreadth = themeBreadth; }
    public BigDecimal getRotationStrength() { return rotationStrength; }
    public void setRotationStrength(BigDecimal rotationStrength) { this.rotationStrength = rotationStrength; }
    public BigDecimal getNarrativeHeat() { return narrativeHeat; }
    public void setNarrativeHeat(BigDecimal narrativeHeat) { this.narrativeHeat = narrativeHeat; }
    public String getCrowdingRisk() { return crowdingRisk; }
    public void setCrowdingRisk(String crowdingRisk) { this.crowdingRisk = crowdingRisk; }
    public String getInstitutionalAlignment() { return institutionalAlignment; }
    public void setInstitutionalAlignment(String institutionalAlignment) { this.institutionalAlignment = institutionalAlignment; }
    public String getWavePhase() { return wavePhase; }
    public void setWavePhase(String wavePhase) { this.wavePhase = wavePhase; }
    public String getMarketContext() { return marketContext; }
    public void setMarketContext(String marketContext) { this.marketContext = marketContext; }
    public String getSectorLeadership() { return sectorLeadership; }
    public void setSectorLeadership(String sectorLeadership) { this.sectorLeadership = sectorLeadership; }
    public Boolean getThemeStillActive() { return themeStillActive; }
    public void setThemeStillActive(Boolean themeStillActive) { this.themeStillActive = themeStillActive; }
    public String getEntryReason() { return entryReason; }
    public void setEntryReason(String entryReason) { this.entryReason = entryReason; }
    public Integer getExpectedHoldingDays() { return expectedHoldingDays; }
    public void setExpectedHoldingDays(Integer expectedHoldingDays) { this.expectedHoldingDays = expectedHoldingDays; }
    public String getInvalidationCondition() { return invalidationCondition; }
    public void setInvalidationCondition(String invalidationCondition) { this.invalidationCondition = invalidationCondition; }
    public String getStopType() { return stopType; }
    public void setStopType(String stopType) { this.stopType = stopType; }
    public String getTargetWave() { return targetWave; }
    public void setTargetWave(String targetWave) { this.targetWave = targetWave; }
    public String getThesisStatus() { return thesisStatus; }
    public void setThesisStatus(String thesisStatus) { this.thesisStatus = thesisStatus; }
    public BigDecimal getThesisConfidence() { return thesisConfidence; }
    public void setThesisConfidence(BigDecimal thesisConfidence) { this.thesisConfidence = thesisConfidence; }
    public LocalDateTime getLatestReviewDate() { return latestReviewDate; }
    public void setLatestReviewDate(LocalDateTime latestReviewDate) { this.latestReviewDate = latestReviewDate; }
    public String getLatestReviewReason() { return latestReviewReason; }
    public void setLatestReviewReason(String latestReviewReason) { this.latestReviewReason = latestReviewReason; }
    public boolean isOpenPosition() { return openPosition; }
    public void setOpenPosition(boolean openPosition) { this.openPosition = openPosition; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public boolean isProductionDecisionAllowed() { return productionDecisionAllowed; }
    public void setProductionDecisionAllowed(boolean productionDecisionAllowed) { this.productionDecisionAllowed = productionDecisionAllowed; }
    public boolean isAutoBuyEnabled() { return autoBuyEnabled; }
    public void setAutoBuyEnabled(boolean autoBuyEnabled) { this.autoBuyEnabled = autoBuyEnabled; }
    public boolean isAutoSellEnabled() { return autoSellEnabled; }
    public void setAutoSellEnabled(boolean autoSellEnabled) { this.autoSellEnabled = autoSellEnabled; }
    public boolean isManualConfirmRequired() { return manualConfirmRequired; }
    public void setManualConfirmRequired(boolean manualConfirmRequired) { this.manualConfirmRequired = manualConfirmRequired; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
