package com.austin.trading.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_theme_radar_trace",
        indexes = {
                @Index(name = "idx_candidate_theme_radar_date", columnList = "trading_date"),
                @Index(name = "idx_candidate_theme_radar_symbol", columnList = "symbol, trading_date"),
                @Index(name = "idx_candidate_theme_radar_theme", columnList = "theme_tag, trading_date")
        })
public class CandidateThemeRadarTraceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "symbol", nullable = false, length = 20) private String symbol;
    @Column(name = "theme_tag", nullable = false, length = 120) private String themeTag;
    @Column(name = "candidate_before_score", precision = 12, scale = 4) private BigDecimal candidateBeforeScore;
    @Column(name = "theme_radar_boost", precision = 12, scale = 4) private BigDecimal themeRadarBoost;
    @Column(name = "candidate_after_score", precision = 12, scale = 4) private BigDecimal candidateAfterScore;
    @Column(name = "applied_to_candidate_pool", nullable = false) private Boolean appliedToCandidatePool = false;
    @Column(name = "applied_to_final_decision", nullable = false) private Boolean appliedToFinalDecision = false;
    @Column(name = "safety_contract_json", columnDefinition = "json") private String safetyContractJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public BigDecimal getCandidateBeforeScore() { return candidateBeforeScore; }
    public void setCandidateBeforeScore(BigDecimal candidateBeforeScore) { this.candidateBeforeScore = candidateBeforeScore; }
    public BigDecimal getThemeRadarBoost() { return themeRadarBoost; }
    public void setThemeRadarBoost(BigDecimal themeRadarBoost) { this.themeRadarBoost = themeRadarBoost; }
    public BigDecimal getCandidateAfterScore() { return candidateAfterScore; }
    public void setCandidateAfterScore(BigDecimal candidateAfterScore) { this.candidateAfterScore = candidateAfterScore; }
    public Boolean getAppliedToCandidatePool() { return appliedToCandidatePool; }
    public void setAppliedToCandidatePool(Boolean appliedToCandidatePool) { this.appliedToCandidatePool = appliedToCandidatePool; }
    public Boolean getAppliedToFinalDecision() { return appliedToFinalDecision; }
    public void setAppliedToFinalDecision(Boolean appliedToFinalDecision) { this.appliedToFinalDecision = appliedToFinalDecision; }
    public String getSafetyContractJson() { return safetyContractJson; }
    public void setSafetyContractJson(String safetyContractJson) { this.safetyContractJson = safetyContractJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
