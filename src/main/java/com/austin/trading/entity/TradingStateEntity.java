package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trading_state")
public class TradingStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "market_grade", length = 20)
    private String marketGrade;

    @Column(name = "decision_lock", length = 20)
    private String decisionLock;

    @Column(name = "time_decay_stage", length = 20)
    private String timeDecayStage;

    @Column(name = "hourly_gate", length = 20)
    private String hourlyGate;

    @Column(name = "monitor_mode", length = 20)
    private String monitorMode;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public LocalDate getTradingDate() {
        return tradingDate;
    }

    public void setTradingDate(LocalDate tradingDate) {
        this.tradingDate = tradingDate;
    }

    public String getMarketGrade() {
        return marketGrade;
    }

    public void setMarketGrade(String marketGrade) {
        this.marketGrade = marketGrade;
    }

    public String getDecisionLock() {
        return decisionLock;
    }

    public void setDecisionLock(String decisionLock) {
        this.decisionLock = decisionLock;
    }

    public String getTimeDecayStage() {
        return timeDecayStage;
    }

    public void setTimeDecayStage(String timeDecayStage) {
        this.timeDecayStage = timeDecayStage;
    }

    public String getHourlyGate() {
        return hourlyGate;
    }

    public void setHourlyGate(String hourlyGate) {
        this.hourlyGate = hourlyGate;
    }

    public String getMonitorMode() {
        return monitorMode;
    }

    public void setMonitorMode(String monitorMode) {
        this.monitorMode = monitorMode;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
