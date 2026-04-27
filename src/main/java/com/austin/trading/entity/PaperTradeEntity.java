package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Phase 1 Paper Trading:forward live 虛擬倉。
 *
 * <p>由 {@code FinalDecisionService.persistAndReturnWithStrategy} 在 ENTER 決策落地後,
 * 透過 {@code PaperTradeService.openOnFinalDecision(...)} 自動建立。
 * 每日 13:35 由 {@code PaperTradeMtmJob} mark-to-market 並判定是否觸發
 * {@code ExitRuleEvaluator} 的出場條件。</p>
 *
 * <p>不發送任何 LINE / Telegram 通知,純記錄。</p>
 */
@Entity
@Table(name = "paper_trade",
        indexes = {
                @Index(name = "idx_paper_trade_status", columnList = "status"),
                @Index(name = "idx_paper_trade_entry_date", columnList = "entry_date"),
                @Index(name = "idx_paper_trade_symbol", columnList = "symbol"),
                @Index(name = "idx_paper_trade_strategy", columnList = "strategy_type")
        })
public class PaperTradeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false, unique = true, length = 40)
    private String tradeId;

    // ── 進場 ─────────────────────────────────────────────────────────
    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "entry_time")
    private LocalTime entryTime;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    /**
     * Intended entry price — derived from candidate {@code entryPriceZone}
     * mid-point or lower bound. Reused as the canonical {@code entry_price}
     * column so existing KPI / MTM logic keeps working unchanged.
     */
    @Column(name = "entry_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal entryPrice;

    /**
     * Snapshot of the intended entry price at decision time. Equal to
     * {@code entry_price} on the same row but kept as a separate column
     * so future回測 comparing intended vs actual fills can join cleanly
     * without parsing payload JSON.
     */
    @Column(name = "intended_entry_price", precision = 12, scale = 4)
    private BigDecimal intendedEntryPrice;

    /**
     * Simulated fill price = live currentPrice * (1 + 0.001) for buys
     * (positive 1‰ slippage). Falls back to intended price when no live
     * quote is available. Used by future回測 to reproduce realistic fills.
     */
    @Column(name = "simulated_entry_price", precision = 12, scale = 4)
    private BigDecimal simulatedEntryPrice;

    @Column(name = "position_shares")
    private Integer positionShares;

    @Column(name = "position_amount", precision = 18, scale = 2)
    private BigDecimal positionAmount;

    @Column(name = "stop_loss_price", precision = 12, scale = 4)
    private BigDecimal stopLossPrice;

    @Column(name = "target1_price", precision = 12, scale = 4)
    private BigDecimal target1Price;

    @Column(name = "target2_price", precision = 12, scale = 4)
    private BigDecimal target2Price;

    @Column(name = "max_holding_days", nullable = false)
    private Integer maxHoldingDays = 5;

    /** CODEX / CLAUDE / HYBRID(由 finalDecision.aiStatus 推導) */
    @Column(name = "source", length = 20)
    private String source;

    /** SETUP / MOMENTUM_CHASE */
    @Column(name = "strategy_type", length = 30)
    private String strategyType;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "final_decision_id")
    private Long finalDecisionId;

    @Column(name = "ai_task_id")
    private Long aiTaskId;

    @Column(name = "final_rank_score", precision = 5, scale = 2)
    private BigDecimal finalRankScore;

    @Column(name = "theme_heat_score", precision = 5, scale = 2)
    private BigDecimal themeHeatScore;

    @Column(name = "expectation_score", precision = 5, scale = 2)
    private BigDecimal expectationScore;

    /** A_PLUS / A_NORMAL / B_TRIAL — entry-time grade snapshot. */
    @Column(name = "entry_grade", length = 10)
    private String entryGrade;

    /** Entry-time risk/reward ratio captured at decision time. */
    @Column(name = "entry_rr_ratio", precision = 6, scale = 3)
    private BigDecimal entryRrRatio;

    /**
     * Market regime at entry time, e.g. {@code BULL_TREND},
     * {@code RANGE_CHOP}, {@code WEAK_DOWNTREND}, {@code PANIC_VOLATILITY}.
     */
    @Column(name = "entry_regime", length = 30)
    private String entryRegime;

    /**
     * Rich snapshot JSON: final decision payload + scoring trace +
     * AI weight override reason + theme exposure context. Kept separate
     * from {@code payload_json} (the legacy decision-level payload) so
     * 回測 can read entry context without parsing the older blob.
     */
    @Lob
    @Column(name = "entry_payload_json", columnDefinition = "longtext")
    private String entryPayloadJson;

    // ── 出場 ─────────────────────────────────────────────────────────
    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "exit_time")
    private LocalTime exitTime;

    @Column(name = "exit_price", precision = 12, scale = 4)
    private BigDecimal exitPrice;

    /** Simulated fill price at exit (live-quote * (1 - 0.001) sell-side slippage). */
    @Column(name = "simulated_exit_price", precision = 12, scale = 4)
    private BigDecimal simulatedExitPrice;

    /** STOP_LOSS / TRAILING_STOP / TP1_HIT / TP2_HIT / REVIEW_EXIT / TIME_EXIT / REVERSE_SIGNAL / MANUAL / VOID */
    @Column(name = "exit_reason", length = 30)
    private String exitReason;

    @Column(name = "pnl_amount", precision = 18, scale = 2)
    private BigDecimal pnlAmount;

    @Column(name = "pnl_pct", precision = 8, scale = 4)
    private BigDecimal pnlPct;

    @Column(name = "holding_days")
    private Integer holdingDays;

    @Column(name = "mfe_pct", precision = 8, scale = 4)
    private BigDecimal mfePct;

    @Column(name = "mae_pct", precision = 8, scale = 4)
    private BigDecimal maePct;

    /** OPEN / CLOSED / VOID */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // ── getters / setters ────────────────────────────────────────────
    public Long getId() { return id; }
    public String getTradeId() { return tradeId; }
    public void setTradeId(String v) { this.tradeId = v; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate v) { this.entryDate = v; }
    public LocalTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalTime v) { this.entryTime = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getStockName() { return stockName; }
    public void setStockName(String v) { this.stockName = v; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal v) { this.entryPrice = v; }
    public BigDecimal getIntendedEntryPrice() { return intendedEntryPrice; }
    public void setIntendedEntryPrice(BigDecimal v) { this.intendedEntryPrice = v; }
    public BigDecimal getSimulatedEntryPrice() { return simulatedEntryPrice; }
    public void setSimulatedEntryPrice(BigDecimal v) { this.simulatedEntryPrice = v; }
    public Integer getPositionShares() { return positionShares; }
    public void setPositionShares(Integer v) { this.positionShares = v; }
    public BigDecimal getPositionAmount() { return positionAmount; }
    public void setPositionAmount(BigDecimal v) { this.positionAmount = v; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal v) { this.stopLossPrice = v; }
    public BigDecimal getTarget1Price() { return target1Price; }
    public void setTarget1Price(BigDecimal v) { this.target1Price = v; }
    public BigDecimal getTarget2Price() { return target2Price; }
    public void setTarget2Price(BigDecimal v) { this.target2Price = v; }
    public Integer getMaxHoldingDays() { return maxHoldingDays; }
    public void setMaxHoldingDays(Integer v) { this.maxHoldingDays = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String v) { this.strategyType = v; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String v) { this.themeTag = v; }
    public Long getFinalDecisionId() { return finalDecisionId; }
    public void setFinalDecisionId(Long v) { this.finalDecisionId = v; }
    public Long getAiTaskId() { return aiTaskId; }
    public void setAiTaskId(Long v) { this.aiTaskId = v; }
    public BigDecimal getFinalRankScore() { return finalRankScore; }
    public void setFinalRankScore(BigDecimal v) { this.finalRankScore = v; }
    public BigDecimal getThemeHeatScore() { return themeHeatScore; }
    public void setThemeHeatScore(BigDecimal v) { this.themeHeatScore = v; }
    public BigDecimal getExpectationScore() { return expectationScore; }
    public void setExpectationScore(BigDecimal v) { this.expectationScore = v; }
    public String getEntryGrade() { return entryGrade; }
    public void setEntryGrade(String v) { this.entryGrade = v; }
    public BigDecimal getEntryRrRatio() { return entryRrRatio; }
    public void setEntryRrRatio(BigDecimal v) { this.entryRrRatio = v; }
    public String getEntryRegime() { return entryRegime; }
    public void setEntryRegime(String v) { this.entryRegime = v; }
    public String getEntryPayloadJson() { return entryPayloadJson; }
    public void setEntryPayloadJson(String v) { this.entryPayloadJson = v; }
    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate v) { this.exitDate = v; }
    public LocalTime getExitTime() { return exitTime; }
    public void setExitTime(LocalTime v) { this.exitTime = v; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal v) { this.exitPrice = v; }
    public BigDecimal getSimulatedExitPrice() { return simulatedExitPrice; }
    public void setSimulatedExitPrice(BigDecimal v) { this.simulatedExitPrice = v; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String v) { this.exitReason = v; }
    public BigDecimal getPnlAmount() { return pnlAmount; }
    public void setPnlAmount(BigDecimal v) { this.pnlAmount = v; }
    public BigDecimal getPnlPct() { return pnlPct; }
    public void setPnlPct(BigDecimal v) { this.pnlPct = v; }
    public Integer getHoldingDays() { return holdingDays; }
    public void setHoldingDays(Integer v) { this.holdingDays = v; }
    public BigDecimal getMfePct() { return mfePct; }
    public void setMfePct(BigDecimal v) { this.mfePct = v; }
    public BigDecimal getMaePct() { return maePct; }
    public void setMaePct(BigDecimal v) { this.maePct = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
