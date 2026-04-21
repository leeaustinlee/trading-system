package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted output of {@link com.austin.trading.engine.TradeAttributionEngine}.
 * One row per closed position (position_id unique).
 */
@Entity
@Table(
        name = "trade_attribution",
        indexes = {
                @Index(name = "idx_attr_position", columnList = "position_id"),
                @Index(name = "idx_attr_symbol",   columnList = "symbol"),
                @Index(name = "idx_attr_setup",    columnList = "setup_type"),
                @Index(name = "idx_attr_regime",   columnList = "regime_type")
        }
)
public class TradeAttributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false, unique = true)
    private Long positionId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    // ── Pipeline context ───────────────────────────────────────────────────

    @Column(name = "setup_type", length = 40)
    private String setupType;

    @Column(name = "regime_type", length = 30)
    private String regimeType;

    @Column(name = "theme_stage", length = 30)
    private String themeStage;

    @Column(name = "timing_mode", length = 30)
    private String timingMode;

    // ── Entry quality ─────────────────────────────────────────────────────

    @Column(name = "ideal_entry_price", precision = 12, scale = 4)
    private BigDecimal idealEntryPrice;

    @Column(name = "actual_entry_price", precision = 12, scale = 4)
    private BigDecimal actualEntryPrice;

    @Column(name = "delay_pct", precision = 8, scale = 4)
    private BigDecimal delayPct;

    // ── Trade excursion ───────────────────────────────────────────────────

    @Column(name = "mfe_pct", precision = 8, scale = 4)
    private BigDecimal mfePct;

    @Column(name = "mae_pct", precision = 8, scale = 4)
    private BigDecimal maePct;

    @Column(name = "pnl_pct", precision = 8, scale = 4)
    private BigDecimal pnlPct;

    // ── Quality scores ────────────────────────────────────────────────────

    @Column(name = "timing_quality", length = 10)
    private String timingQuality;

    @Column(name = "exit_quality", length = 10)
    private String exitQuality;

    @Column(name = "sizing_quality", length = 10)
    private String sizingQuality;

    // ── Upstream attribution IDs (plain Long, no FK) ──────────────────────

    @Column(name = "regime_decision_id")
    private Long regimeDecisionId;

    @Column(name = "setup_decision_id")
    private Long setupDecisionId;

    @Column(name = "timing_decision_id")
    private Long timingDecisionId;

    @Column(name = "theme_decision_id")
    private Long themeDecisionId;

    @Column(name = "execution_decision_id")
    private Long executionDecisionId;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ──────────────────────────────────────────────────

    public Long getId() { return id; }

    public Long getPositionId() { return positionId; }
    public void setPositionId(Long v) { this.positionId = v; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }

    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate v) { this.entryDate = v; }

    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate v) { this.exitDate = v; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String v) { this.setupType = v; }

    public String getRegimeType() { return regimeType; }
    public void setRegimeType(String v) { this.regimeType = v; }

    public String getThemeStage() { return themeStage; }
    public void setThemeStage(String v) { this.themeStage = v; }

    public String getTimingMode() { return timingMode; }
    public void setTimingMode(String v) { this.timingMode = v; }

    public BigDecimal getIdealEntryPrice() { return idealEntryPrice; }
    public void setIdealEntryPrice(BigDecimal v) { this.idealEntryPrice = v; }

    public BigDecimal getActualEntryPrice() { return actualEntryPrice; }
    public void setActualEntryPrice(BigDecimal v) { this.actualEntryPrice = v; }

    public BigDecimal getDelayPct() { return delayPct; }
    public void setDelayPct(BigDecimal v) { this.delayPct = v; }

    public BigDecimal getMfePct() { return mfePct; }
    public void setMfePct(BigDecimal v) { this.mfePct = v; }

    public BigDecimal getMaePct() { return maePct; }
    public void setMaePct(BigDecimal v) { this.maePct = v; }

    public BigDecimal getPnlPct() { return pnlPct; }
    public void setPnlPct(BigDecimal v) { this.pnlPct = v; }

    public String getTimingQuality() { return timingQuality; }
    public void setTimingQuality(String v) { this.timingQuality = v; }

    public String getExitQuality() { return exitQuality; }
    public void setExitQuality(String v) { this.exitQuality = v; }

    public String getSizingQuality() { return sizingQuality; }
    public void setSizingQuality(String v) { this.sizingQuality = v; }

    public Long getRegimeDecisionId() { return regimeDecisionId; }
    public void setRegimeDecisionId(Long v) { this.regimeDecisionId = v; }

    public Long getSetupDecisionId() { return setupDecisionId; }
    public void setSetupDecisionId(Long v) { this.setupDecisionId = v; }

    public Long getTimingDecisionId() { return timingDecisionId; }
    public void setTimingDecisionId(Long v) { this.timingDecisionId = v; }

    public Long getThemeDecisionId() { return themeDecisionId; }
    public void setThemeDecisionId(Long v) { this.themeDecisionId = v; }

    public Long getExecutionDecisionId() { return executionDecisionId; }
    public void setExecutionDecisionId(Long v) { this.executionDecisionId = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
