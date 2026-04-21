package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted output of {@link com.austin.trading.engine.ExecutionDecisionEngine}.
 * Upstream decision IDs are stored as plain Long references (not FK constraints)
 * so the table is portable and self-contained for attribution queries.
 */
@Entity
@Table(
        name = "execution_decision_log",
        indexes = {
                @Index(name = "idx_exec_date_symbol", columnList = "trading_date, symbol"),
                @Index(name = "idx_exec_date_action", columnList = "trading_date, action")
        }
)
public class ExecutionDecisionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    /** ENTER | SKIP | EXIT | WEAKEN */
    @Column(name = "action", nullable = false, length = 10)
    private String action;

    /** CONFIRMED | CODEX_VETO | BASE_ACTION_SKIP | BASE_ACTION_REST | etc. */
    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "codex_vetoed", nullable = false)
    private boolean codexVetoed;

    // ── Upstream attribution IDs (nullable; set by ExecutionDecisionService) ──

    /** References market_regime_decision.id */
    @Column(name = "regime_decision_id")
    private Long regimeDecisionId;

    /** References stock_ranking_snapshot.id */
    @Column(name = "ranking_snapshot_id")
    private Long rankingSnapshotId;

    /** References setup_decision_log.id */
    @Column(name = "setup_decision_id")
    private Long setupDecisionId;

    /** References execution_timing_decision.id */
    @Column(name = "timing_decision_id")
    private Long timingDecisionId;

    /** References portfolio_risk_decision.id */
    @Column(name = "risk_decision_id")
    private Long riskDecisionId;

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

    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String v) { this.reasonCode = v; }

    public boolean isCodexVetoed() { return codexVetoed; }
    public void setCodexVetoed(boolean v) { this.codexVetoed = v; }

    public Long getRegimeDecisionId() { return regimeDecisionId; }
    public void setRegimeDecisionId(Long v) { this.regimeDecisionId = v; }

    public Long getRankingSnapshotId() { return rankingSnapshotId; }
    public void setRankingSnapshotId(Long v) { this.rankingSnapshotId = v; }

    public Long getSetupDecisionId() { return setupDecisionId; }
    public void setSetupDecisionId(Long v) { this.setupDecisionId = v; }

    public Long getTimingDecisionId() { return timingDecisionId; }
    public void setTimingDecisionId(Long v) { this.timingDecisionId = v; }

    public Long getRiskDecisionId() { return riskDecisionId; }
    public void setRiskDecisionId(Long v) { this.riskDecisionId = v; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
