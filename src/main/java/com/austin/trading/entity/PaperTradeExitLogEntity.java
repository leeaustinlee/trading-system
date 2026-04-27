package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit row for every {@code PaperTradeExitJob} evaluation.
 *
 * <p>Written by both FIRED (a real exit happened) and SKIPPED outcomes so that we can answer
 * "why didn't this trade exit at 09:35?" without re-running the cron.</p>
 *
 * <p>Schema is created by Hibernate auto-DDL — no manual migration needed.</p>
 */
@Entity
@Table(name = "paper_trade_exit_log",
        indexes = {
                @Index(name = "idx_paper_exit_log_trade", columnList = "paper_trade_id"),
                @Index(name = "idx_paper_exit_log_time",  columnList = "evaluated_at"),
                @Index(name = "idx_paper_exit_log_outcome", columnList = "trigger_outcome")
        })
public class PaperTradeExitLogEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_trade_id", nullable = false)
    private Long paperTradeId;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    /** FIRED / SKIPPED_NOT_TRIGGERED / SKIPPED_DISABLED */
    @Column(name = "trigger_outcome", nullable = false, length = 30)
    private String triggerOutcome;

    /** STOP_LOSS / TRAILING_STOP / TP2_HIT / TP1_HIT / REVIEW_EXIT / TIME_EXIT / REVERSE_SIGNAL */
    @Column(name = "exit_reason", length = 30)
    private String exitReason;

    /** 1-7 — matches the priority order in PaperTradeService.attemptExit */
    @Column(name = "trigger_priority")
    private Integer triggerPriority;

    @Column(name = "current_price", precision = 12, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "stop_loss_price", precision = 12, scale = 4)
    private BigDecimal stopLossPrice;

    @Column(name = "tp1_price", precision = 12, scale = 4)
    private BigDecimal tp1Price;

    @Column(name = "tp2_price", precision = 12, scale = 4)
    private BigDecimal tp2Price;

    @Column(name = "trailing_stop_price", precision = 12, scale = 4)
    private BigDecimal trailingStopPrice;

    @Column(name = "review_status", length = 20)
    private String reviewStatus;

    @Column(name = "hold_days")
    private Integer holdDays;

    @Column(name = "reverse_signal_decision", length = 20)
    private String reverseSignalDecision;

    @Column(name = "reverse_signal_reason", length = 255)
    private String reverseSignalReason;

    @Lob
    @Column(name = "detail_json", columnDefinition = "longtext")
    private String detailJson;

    // ── getters / setters ────────────────────────────────────────────
    public Long getId() { return id; }
    public Long getPaperTradeId() { return paperTradeId; }
    public void setPaperTradeId(Long v) { this.paperTradeId = v; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime v) { this.evaluatedAt = v; }
    public String getTriggerOutcome() { return triggerOutcome; }
    public void setTriggerOutcome(String v) { this.triggerOutcome = v; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String v) { this.exitReason = v; }
    public Integer getTriggerPriority() { return triggerPriority; }
    public void setTriggerPriority(Integer v) { this.triggerPriority = v; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal v) { this.currentPrice = v; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal v) { this.stopLossPrice = v; }
    public BigDecimal getTp1Price() { return tp1Price; }
    public void setTp1Price(BigDecimal v) { this.tp1Price = v; }
    public BigDecimal getTp2Price() { return tp2Price; }
    public void setTp2Price(BigDecimal v) { this.tp2Price = v; }
    public BigDecimal getTrailingStopPrice() { return trailingStopPrice; }
    public void setTrailingStopPrice(BigDecimal v) { this.trailingStopPrice = v; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String v) { this.reviewStatus = v; }
    public Integer getHoldDays() { return holdDays; }
    public void setHoldDays(Integer v) { this.holdDays = v; }
    public String getReverseSignalDecision() { return reverseSignalDecision; }
    public void setReverseSignalDecision(String v) { this.reverseSignalDecision = v; }
    public String getReverseSignalReason() { return reverseSignalReason; }
    public void setReverseSignalReason(String v) { this.reverseSignalReason = v; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String v) { this.detailJson = v; }
}
