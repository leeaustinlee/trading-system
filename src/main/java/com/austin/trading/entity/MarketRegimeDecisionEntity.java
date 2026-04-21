package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted market regime classification, produced by
 * {@link com.austin.trading.engine.MarketRegimeEngine} and owned by
 * {@link com.austin.trading.service.MarketRegimeService}.
 *
 * <p>This table is the <b>authoritative source</b> for which setup types may
 * trade today and what risk multiplier applies. Downstream layers must read
 * this, not re-derive regime meaning from A/B/C grade or snapshots.</p>
 */
@Entity
@Table(
        name = "market_regime_decision",
        indexes = {
                @Index(name = "idx_regime_date",       columnList = "trading_date"),
                @Index(name = "idx_regime_date_eval",  columnList = "trading_date, evaluated_at")
        }
)
public class MarketRegimeDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    /** One of {@code BULL_TREND / RANGE_CHOP / WEAK_DOWNTREND / PANIC_VOLATILITY}. */
    @Column(name = "regime_type", nullable = false, length = 30)
    private String regimeType;

    /** Legacy A/B/C grade captured at evaluation time for audit, not routing. */
    @Column(name = "market_grade", length = 10)
    private String marketGrade;

    @Column(name = "trade_allowed", nullable = false)
    private boolean tradeAllowed;

    @Column(name = "risk_multiplier", precision = 6, scale = 3)
    private BigDecimal riskMultiplier;

    /** JSON array of setup type strings, e.g. {@code ["BREAKOUT_CONTINUATION","PULLBACK_CONFIRMATION"]}. */
    @Column(name = "allowed_setup_types_json", columnDefinition = "json")
    private String allowedSetupTypesJson;

    @Column(name = "summary", length = 255)
    private String summary;

    /** JSON array of rule hits / explanations. */
    @Column(name = "reasons_json", columnDefinition = "json")
    private String reasonsJson;

    /** Snapshot of the engine input fields (incl. which were fallbacks). */
    @Column(name = "input_snapshot_json", columnDefinition = "json")
    private String inputSnapshotJson;

    /** Upstream market_snapshot id, if available (loose reference, no FK). */
    @Column(name = "market_snapshot_id")
    private Long marketSnapshotId;

    /** Engine version for future migrations. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }

    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }

    public String getRegimeType() { return regimeType; }
    public void setRegimeType(String regimeType) { this.regimeType = regimeType; }

    public String getMarketGrade() { return marketGrade; }
    public void setMarketGrade(String marketGrade) { this.marketGrade = marketGrade; }

    public boolean isTradeAllowed() { return tradeAllowed; }
    public void setTradeAllowed(boolean tradeAllowed) { this.tradeAllowed = tradeAllowed; }

    public BigDecimal getRiskMultiplier() { return riskMultiplier; }
    public void setRiskMultiplier(BigDecimal riskMultiplier) { this.riskMultiplier = riskMultiplier; }

    public String getAllowedSetupTypesJson() { return allowedSetupTypesJson; }
    public void setAllowedSetupTypesJson(String allowedSetupTypesJson) { this.allowedSetupTypesJson = allowedSetupTypesJson; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String reasonsJson) { this.reasonsJson = reasonsJson; }

    public String getInputSnapshotJson() { return inputSnapshotJson; }
    public void setInputSnapshotJson(String inputSnapshotJson) { this.inputSnapshotJson = inputSnapshotJson; }

    public Long getMarketSnapshotId() { return marketSnapshotId; }
    public void setMarketSnapshotId(Long marketSnapshotId) { this.marketSnapshotId = marketSnapshotId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
