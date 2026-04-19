package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日排程狀態記錄。
 * <p>
 * 以 {@code trading_date} 為主鍵，每個欄位對應一個 scheduler job 的當日執行狀態，
 * 用於：(1) 防止重啟系統時重複執行同一天的 job；(2) 一鍵補跑缺漏的步驟。
 * </p>
 *
 * <p>狀態值：{@code PENDING / RUNNING / DONE / FAILED / SKIPPED}</p>
 */
@Entity
@Table(name = "daily_orchestration_status")
public class DailyOrchestrationStatusEntity {

    @Id
    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "step_premarket_data_prep", length = 20)
    private String stepPremarketDataPrep;

    @Column(name = "step_premarket_notify", length = 20)
    private String stepPremarketNotify;

    @Column(name = "step_open_data_prep", length = 20)
    private String stepOpenDataPrep;

    @Column(name = "step_final_decision", length = 20)
    private String stepFinalDecision;

    @Column(name = "step_hourly_gate", length = 20)
    private String stepHourlyGate;

    @Column(name = "step_five_minute_monitor", length = 20)
    private String stepFiveMinuteMonitor;

    @Column(name = "step_midday_review", length = 20)
    private String stepMiddayReview;

    @Column(name = "step_aftermarket_review", length = 20)
    private String stepAftermarketReview;

    @Column(name = "step_postmarket_data_prep", length = 20)
    private String stepPostmarketDataPrep;

    @Column(name = "step_postmarket_analysis", length = 20)
    private String stepPostmarketAnalysis;

    @Column(name = "step_watchlist_refresh", length = 20)
    private String stepWatchlistRefresh;

    @Column(name = "step_t86_data_prep", length = 20)
    private String stepT86DataPrep;

    @Column(name = "step_tomorrow_plan", length = 20)
    private String stepTomorrowPlan;

    @Column(name = "step_external_probe_health", length = 20)
    private String stepExternalProbeHealth;

    @Column(name = "step_weekly_trade_review", length = 20)
    private String stepWeeklyTradeReview;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** JPA lifecycle hook：每次 INSERT / UPDATE 前自動寫入當下時間。 */
    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── Getters / Setters ───────────────────────────────────────────────

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }

    public String getStepPremarketDataPrep() { return stepPremarketDataPrep; }
    public void setStepPremarketDataPrep(String v) { this.stepPremarketDataPrep = v; }

    public String getStepPremarketNotify() { return stepPremarketNotify; }
    public void setStepPremarketNotify(String v) { this.stepPremarketNotify = v; }

    public String getStepOpenDataPrep() { return stepOpenDataPrep; }
    public void setStepOpenDataPrep(String v) { this.stepOpenDataPrep = v; }

    public String getStepFinalDecision() { return stepFinalDecision; }
    public void setStepFinalDecision(String v) { this.stepFinalDecision = v; }

    public String getStepHourlyGate() { return stepHourlyGate; }
    public void setStepHourlyGate(String v) { this.stepHourlyGate = v; }

    public String getStepFiveMinuteMonitor() { return stepFiveMinuteMonitor; }
    public void setStepFiveMinuteMonitor(String v) { this.stepFiveMinuteMonitor = v; }

    public String getStepMiddayReview() { return stepMiddayReview; }
    public void setStepMiddayReview(String v) { this.stepMiddayReview = v; }

    public String getStepAftermarketReview() { return stepAftermarketReview; }
    public void setStepAftermarketReview(String v) { this.stepAftermarketReview = v; }

    public String getStepPostmarketDataPrep() { return stepPostmarketDataPrep; }
    public void setStepPostmarketDataPrep(String v) { this.stepPostmarketDataPrep = v; }

    public String getStepPostmarketAnalysis() { return stepPostmarketAnalysis; }
    public void setStepPostmarketAnalysis(String v) { this.stepPostmarketAnalysis = v; }

    public String getStepWatchlistRefresh() { return stepWatchlistRefresh; }
    public void setStepWatchlistRefresh(String v) { this.stepWatchlistRefresh = v; }

    public String getStepT86DataPrep() { return stepT86DataPrep; }
    public void setStepT86DataPrep(String v) { this.stepT86DataPrep = v; }

    public String getStepTomorrowPlan() { return stepTomorrowPlan; }
    public void setStepTomorrowPlan(String v) { this.stepTomorrowPlan = v; }

    public String getStepExternalProbeHealth() { return stepExternalProbeHealth; }
    public void setStepExternalProbeHealth(String v) { this.stepExternalProbeHealth = v; }

    public String getStepWeeklyTradeReview() { return stepWeeklyTradeReview; }
    public void setStepWeeklyTradeReview(String v) { this.stepWeeklyTradeReview = v; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
