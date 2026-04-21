package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted output of {@link com.austin.trading.engine.ExecutionTimingEngine}.
 * One row per (trading_date, symbol) evaluation; latest row wins when queried.
 */
@Entity
@Table(
        name = "execution_timing_decision",
        indexes = {
                @Index(name = "idx_timing_date_symbol",   columnList = "trading_date, symbol"),
                @Index(name = "idx_timing_date_approved", columnList = "trading_date, approved")
        }
)
public class ExecutionTimingDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    /** Mirrors SetupDecision.setupType; null when no valid setup was found. */
    @Column(name = "setup_type", length = 30)
    private String setupType;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    /** BREAKOUT_READY | PULLBACK_BOUNCE | EVENT_LAUNCH | WAIT | STALE | NO_SETUP */
    @Column(name = "timing_mode", nullable = false, length = 30)
    private String timingMode;

    /** HIGH | MEDIUM | LOW */
    @Column(name = "urgency", nullable = false, length = 10)
    private String urgency;

    @Column(name = "stale_signal", nullable = false)
    private boolean staleSignal;

    @Column(name = "delay_tolerance_days", nullable = false)
    private int delayToleranceDays;

    @Column(name = "signal_age_days", nullable = false)
    private int signalAgeDays;

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

    public boolean isApproved() { return approved; }
    public void setApproved(boolean v) { this.approved = v; }

    public String getTimingMode() { return timingMode; }
    public void setTimingMode(String v) { this.timingMode = v; }

    public String getUrgency() { return urgency; }
    public void setUrgency(String v) { this.urgency = v; }

    public boolean isStaleSignal() { return staleSignal; }
    public void setStaleSignal(boolean v) { this.staleSignal = v; }

    public int getDelayToleranceDays() { return delayToleranceDays; }
    public void setDelayToleranceDays(int v) { this.delayToleranceDays = v; }

    public int getSignalAgeDays() { return signalAgeDays; }
    public void setSignalAgeDays(int v) { this.signalAgeDays = v; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
