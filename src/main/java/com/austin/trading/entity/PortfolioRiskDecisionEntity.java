package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted output of {@link com.austin.trading.engine.PortfolioRiskEngine}.
 * Portfolio-gate rows have {@code symbol = null}; per-candidate rows have a symbol.
 */
@Entity
@Table(
        name = "portfolio_risk_decision",
        indexes = {
                @Index(name = "idx_risk_date_symbol",   columnList = "trading_date, symbol"),
                @Index(name = "idx_risk_date_approved", columnList = "trading_date, approved")
        }
)
public class PortfolioRiskDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    /** PORTFOLIO_FULL | THEME_OVER_EXPOSED | ALREADY_HELD | null */
    @Column(name = "block_reason", length = 50)
    private String blockReason;

    @Column(name = "open_position_count", nullable = false)
    private int openPositionCount;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions;

    @Column(name = "candidate_theme", length = 100)
    private String candidateTheme;

    @Column(name = "theme_exposure_pct", precision = 8, scale = 4)
    private BigDecimal themeExposurePct;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ─────────────────────────────────────────────────

    public Long getId() { return id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate d) { this.tradingDate = d; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String s) { this.symbol = s; }

    public boolean isApproved() { return approved; }
    public void setApproved(boolean v) { this.approved = v; }

    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String v) { this.blockReason = v; }

    public int getOpenPositionCount() { return openPositionCount; }
    public void setOpenPositionCount(int v) { this.openPositionCount = v; }

    public int getMaxPositions() { return maxPositions; }
    public void setMaxPositions(int v) { this.maxPositions = v; }

    public String getCandidateTheme() { return candidateTheme; }
    public void setCandidateTheme(String v) { this.candidateTheme = v; }

    public BigDecimal getThemeExposurePct() { return themeExposurePct; }
    public void setThemeExposurePct(BigDecimal v) { this.themeExposurePct = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
