package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted setup classification for one candidate on one trading day.
 *
 * <p>Produced by {@link com.austin.trading.service.SetupValidationService}
 * and consumed by the Timing layer (P0.4) to confirm intraday entry.</p>
 */
@Entity
@Table(
        name = "setup_decision_log",
        indexes = {
                @Index(name = "idx_setup_date_symbol", columnList = "trading_date, symbol"),
                @Index(name = "idx_setup_date_valid",  columnList = "trading_date, valid")
        }
)
public class SetupDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    /** BREAKOUT_CONTINUATION | PULLBACK_CONFIRMATION | EVENT_SECOND_LEG | null if all rejected */
    @Column(name = "setup_type", length = 30)
    private String setupType;

    @Column(name = "valid", nullable = false)
    private boolean valid;

    @Column(name = "entry_zone_low",  precision = 12, scale = 4)
    private BigDecimal entryZoneLow;

    @Column(name = "entry_zone_high", precision = 12, scale = 4)
    private BigDecimal entryZoneHigh;

    @Column(name = "ideal_entry_price", precision = 12, scale = 4)
    private BigDecimal idealEntryPrice;

    @Column(name = "invalidation_price", precision = 12, scale = 4)
    private BigDecimal invalidationPrice;

    @Column(name = "initial_stop_price", precision = 12, scale = 4)
    private BigDecimal initialStopPrice;

    @Column(name = "take_profit_1_price", precision = 12, scale = 4)
    private BigDecimal takeProfit1Price;

    @Column(name = "take_profit_2_price", precision = 12, scale = 4)
    private BigDecimal takeProfit2Price;

    @Column(name = "trailing_mode", length = 20)
    private String trailingMode;

    @Column(name = "holding_window_days")
    private Integer holdingWindowDays;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ─────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate d) { this.tradingDate = d; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String s) { this.symbol = s; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String t) { this.setupType = t; }

    public boolean isValid() { return valid; }
    public void setValid(boolean v) { this.valid = v; }

    public BigDecimal getEntryZoneLow() { return entryZoneLow; }
    public void setEntryZoneLow(BigDecimal v) { this.entryZoneLow = v; }

    public BigDecimal getEntryZoneHigh() { return entryZoneHigh; }
    public void setEntryZoneHigh(BigDecimal v) { this.entryZoneHigh = v; }

    public BigDecimal getIdealEntryPrice() { return idealEntryPrice; }
    public void setIdealEntryPrice(BigDecimal v) { this.idealEntryPrice = v; }

    public BigDecimal getInvalidationPrice() { return invalidationPrice; }
    public void setInvalidationPrice(BigDecimal v) { this.invalidationPrice = v; }

    public BigDecimal getInitialStopPrice() { return initialStopPrice; }
    public void setInitialStopPrice(BigDecimal v) { this.initialStopPrice = v; }

    public BigDecimal getTakeProfit1Price() { return takeProfit1Price; }
    public void setTakeProfit1Price(BigDecimal v) { this.takeProfit1Price = v; }

    public BigDecimal getTakeProfit2Price() { return takeProfit2Price; }
    public void setTakeProfit2Price(BigDecimal v) { this.takeProfit2Price = v; }

    public String getTrailingMode() { return trailingMode; }
    public void setTrailingMode(String v) { this.trailingMode = v; }

    public Integer getHoldingWindowDays() { return holdingWindowDays; }
    public void setHoldingWindowDays(Integer v) { this.holdingWindowDays = v; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
