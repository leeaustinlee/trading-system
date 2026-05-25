package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "narrative_shadow_review")
public class NarrativeShadowReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false, unique = true)
    private LocalDate tradingDate;

    @Column(name = "theme_summary_json", columnDefinition = "json")
    private String themeSummaryJson;

    @Column(name = "lifecycle_board_json", columnDefinition = "json")
    private String lifecycleBoardJson;

    @Column(name = "candidate_impact_json", columnDefinition = "json")
    private String candidateImpactJson;

    @Column(name = "warnings_json", columnDefinition = "json")
    private String warningsJson;

    @Column(name = "rotation_analysis_json", columnDefinition = "json")
    private String rotationAnalysisJson;

    @Column(name = "metrics_json", columnDefinition = "json")
    private String metricsJson;

    @Column(name = "guardrail", length = 1000)
    private String guardrail;

    @Column(name = "weak_signal_only", nullable = false)
    private boolean weakSignalOnly = true;

    @Column(name = "shadow_only", nullable = false)
    private boolean shadowOnly = true;

    @Column(name = "observability_only", nullable = false)
    private boolean observabilityOnly = true;

    @Column(name = "production_decision_allowed", nullable = false)
    private boolean productionDecisionAllowed = false;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "generated_at", insertable = false, updatable = false)
    private LocalDateTime generatedAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeSummaryJson() { return themeSummaryJson; }
    public void setThemeSummaryJson(String themeSummaryJson) { this.themeSummaryJson = themeSummaryJson; }
    public String getLifecycleBoardJson() { return lifecycleBoardJson; }
    public void setLifecycleBoardJson(String lifecycleBoardJson) { this.lifecycleBoardJson = lifecycleBoardJson; }
    public String getCandidateImpactJson() { return candidateImpactJson; }
    public void setCandidateImpactJson(String candidateImpactJson) { this.candidateImpactJson = candidateImpactJson; }
    public String getWarningsJson() { return warningsJson; }
    public void setWarningsJson(String warningsJson) { this.warningsJson = warningsJson; }
    public String getRotationAnalysisJson() { return rotationAnalysisJson; }
    public void setRotationAnalysisJson(String rotationAnalysisJson) { this.rotationAnalysisJson = rotationAnalysisJson; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getGuardrail() { return guardrail; }
    public void setGuardrail(String guardrail) { this.guardrail = guardrail; }
    public boolean isWeakSignalOnly() { return weakSignalOnly; }
    public void setWeakSignalOnly(boolean weakSignalOnly) { this.weakSignalOnly = weakSignalOnly; }
    public boolean isShadowOnly() { return shadowOnly; }
    public void setShadowOnly(boolean shadowOnly) { this.shadowOnly = shadowOnly; }
    public boolean isObservabilityOnly() { return observabilityOnly; }
    public void setObservabilityOnly(boolean observabilityOnly) { this.observabilityOnly = observabilityOnly; }
    public boolean isProductionDecisionAllowed() { return productionDecisionAllowed; }
    public void setProductionDecisionAllowed(boolean productionDecisionAllowed) { this.productionDecisionAllowed = productionDecisionAllowed; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
}
