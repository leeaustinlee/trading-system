package com.austin.trading.entity;

import com.austin.trading.domain.enums.ThemeAdmissionShadowAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Shadow-only theme admission decision skeleton.
 *
 * <p>Stores observable admission output for later comparison. It must not drive
 * production candidate/watchlist/buy/sell/risk decisions.</p>
 */
@Entity
@Table(name = "theme_admission_shadow_decision",
        uniqueConstraints = @UniqueConstraint(name = "uk_theme_admission_shadow_date_symbol_theme_signal",
                columnNames = {"trading_date", "symbol", "theme_tag", "signal_id"}),
        indexes = {
                @Index(name = "idx_theme_admission_shadow_date", columnList = "trading_date"),
                @Index(name = "idx_theme_admission_shadow_symbol_date", columnList = "symbol, trading_date"),
                @Index(name = "idx_theme_admission_shadow_theme_date", columnList = "theme_tag, trading_date"),
                @Index(name = "idx_theme_admission_shadow_action", columnList = "shadow_action, trading_date")
        })
public class ThemeAdmissionShadowDecisionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;
    @Column(name = "stock_name", length = 120)
    private String stockName;
    @Column(name = "theme_tag", nullable = false, length = 120)
    private String themeTag;
    @Column(name = "signal_id")
    private Long signalId;
    @Column(name = "signal_role", length = 40)
    private String signalRole;
    @Column(name = "current_action", length = 60)
    private String currentAction;
    @Column(name = "current_reason", length = 500)
    private String currentReason;
    @Enumerated(EnumType.STRING)
    @Column(name = "shadow_action", nullable = false, length = 40)
    private ThemeAdmissionShadowAction shadowAction;
    @Column(name = "shadow_reason", length = 500)
    private String shadowReason;
    @Column(name = "would_write_candidate")
    private Boolean wouldWriteCandidate;
    @Column(name = "would_write_watchlist")
    private Boolean wouldWriteWatchlist;
    @Column(name = "would_create_pullback_plan")
    private Boolean wouldCreatePullbackPlan;
    @Column(name = "would_bypass_top_n")
    private Boolean wouldBypassTopN;
    @Column(name = "blocked_by_current_stage", length = 80)
    private String blockedByCurrentStage;
    @Column(name = "delta_stage", length = 80)
    private String deltaStage;
    @Column(name = "admission_score", precision = 12, scale = 4)
    private BigDecimal admissionScore;
    @Column(name = "theme_strength", precision = 12, scale = 4)
    private BigDecimal themeStrength;
    @Column(name = "signal_strength", precision = 12, scale = 4)
    private BigDecimal signalStrength;
    @Column(name = "rank_in_theme")
    private Integer rankInTheme;
    @Column(name = "near_limit")
    private Boolean nearLimit;
    @Column(name = "limit_risk", length = 80)
    private String limitRisk;
    @Column(name = "source_trace_id")
    private Long sourceTraceId;
    @Column(name = "evidence_json", columnDefinition = "json")
    private String evidenceJson;
    @Column(name = "trace_source", nullable = false, length = 40)
    private String traceSource = "SHADOW";
    @Column(name = "trace_status", nullable = false, length = 40)
    private String traceStatus = "ACTIVE";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) { createdAt = now; }
        if (updatedAt == null) { updatedAt = now; }
        if (traceSource == null) { traceSource = "SHADOW"; }
        if (traceStatus == null) { traceStatus = "ACTIVE"; }
    }

    @PreUpdate
    public void preUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public Long getSignalId() { return signalId; }
    public void setSignalId(Long signalId) { this.signalId = signalId; }
    public String getSignalRole() { return signalRole; }
    public void setSignalRole(String signalRole) { this.signalRole = signalRole; }
    public String getCurrentAction() { return currentAction; }
    public void setCurrentAction(String currentAction) { this.currentAction = currentAction; }
    public String getCurrentReason() { return currentReason; }
    public void setCurrentReason(String currentReason) { this.currentReason = currentReason; }
    public ThemeAdmissionShadowAction getShadowAction() { return shadowAction; }
    public void setShadowAction(ThemeAdmissionShadowAction shadowAction) { this.shadowAction = shadowAction; }
    public String getShadowReason() { return shadowReason; }
    public void setShadowReason(String shadowReason) { this.shadowReason = shadowReason; }
    public Boolean getWouldWriteCandidate() { return wouldWriteCandidate; }
    public void setWouldWriteCandidate(Boolean wouldWriteCandidate) { this.wouldWriteCandidate = wouldWriteCandidate; }
    public Boolean getWouldWriteWatchlist() { return wouldWriteWatchlist; }
    public void setWouldWriteWatchlist(Boolean wouldWriteWatchlist) { this.wouldWriteWatchlist = wouldWriteWatchlist; }
    public Boolean getWouldCreatePullbackPlan() { return wouldCreatePullbackPlan; }
    public void setWouldCreatePullbackPlan(Boolean wouldCreatePullbackPlan) { this.wouldCreatePullbackPlan = wouldCreatePullbackPlan; }
    public Boolean getWouldBypassTopN() { return wouldBypassTopN; }
    public void setWouldBypassTopN(Boolean wouldBypassTopN) { this.wouldBypassTopN = wouldBypassTopN; }
    public String getBlockedByCurrentStage() { return blockedByCurrentStage; }
    public void setBlockedByCurrentStage(String blockedByCurrentStage) { this.blockedByCurrentStage = blockedByCurrentStage; }
    public String getDeltaStage() { return deltaStage; }
    public void setDeltaStage(String deltaStage) { this.deltaStage = deltaStage; }
    public BigDecimal getAdmissionScore() { return admissionScore; }
    public void setAdmissionScore(BigDecimal admissionScore) { this.admissionScore = admissionScore; }
    public BigDecimal getThemeStrength() { return themeStrength; }
    public void setThemeStrength(BigDecimal themeStrength) { this.themeStrength = themeStrength; }
    public BigDecimal getSignalStrength() { return signalStrength; }
    public void setSignalStrength(BigDecimal signalStrength) { this.signalStrength = signalStrength; }
    public Integer getRankInTheme() { return rankInTheme; }
    public void setRankInTheme(Integer rankInTheme) { this.rankInTheme = rankInTheme; }
    public Boolean getNearLimit() { return nearLimit; }
    public void setNearLimit(Boolean nearLimit) { this.nearLimit = nearLimit; }
    public String getLimitRisk() { return limitRisk; }
    public void setLimitRisk(String limitRisk) { this.limitRisk = limitRisk; }
    public Long getSourceTraceId() { return sourceTraceId; }
    public void setSourceTraceId(Long sourceTraceId) { this.sourceTraceId = sourceTraceId; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getTraceSource() { return traceSource; }
    public void setTraceSource(String traceSource) { this.traceSource = traceSource; }
    public String getTraceStatus() { return traceStatus; }
    public void setTraceStatus(String traceStatus) { this.traceStatus = traceStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
