package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted output of {@link com.austin.trading.engine.ThemeStrengthEngine}.
 * One row per (trading_date, theme_tag).
 */
@Entity
@Table(
        name = "theme_strength_decision",
        indexes = {
                @Index(name = "idx_theme_str_date_tag", columnList = "trading_date, theme_tag")
        }
)
public class ThemeStrengthDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "theme_tag", nullable = false, length = 100)
    private String themeTag;

    @Column(name = "strength_score", precision = 6, scale = 4)
    private BigDecimal strengthScore;

    /** EARLY_EXPANSION | MID_TREND | LATE_EXTENSION | DECAY */
    @Column(name = "theme_stage", length = 30)
    private String themeStage;

    @Column(name = "catalyst_type", length = 50)
    private String catalystType;

    @Column(name = "tradable", nullable = false)
    private boolean tradable;

    @Column(name = "decay_risk", precision = 6, scale = 4)
    private BigDecimal decayRisk;

    @Column(name = "reasons_json", columnDefinition = "json")
    private String reasonsJson;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ──────────────────────────────────────────────────

    public Long getId() { return id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate v) { this.tradingDate = v; }

    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String v) { this.themeTag = v; }

    public BigDecimal getStrengthScore() { return strengthScore; }
    public void setStrengthScore(BigDecimal v) { this.strengthScore = v; }

    public String getThemeStage() { return themeStage; }
    public void setThemeStage(String v) { this.themeStage = v; }

    public String getCatalystType() { return catalystType; }
    public void setCatalystType(String v) { this.catalystType = v; }

    public boolean isTradable() { return tradable; }
    public void setTradable(boolean v) { this.tradable = v; }

    public BigDecimal getDecayRisk() { return decayRisk; }
    public void setDecayRisk(BigDecimal v) { this.decayRisk = v; }

    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String v) { this.reasonsJson = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
