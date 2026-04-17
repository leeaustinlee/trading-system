package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "monitor_decision")
public class MonitorDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "decision_time", nullable = false)
    private LocalDateTime decisionTime;

    @Column(name = "monitor_mode", nullable = false, length = 20)
    private String monitorMode;

    @Column(name = "should_notify")
    private Boolean shouldNotify;

    @Column(name = "trigger_event", length = 50)
    private String triggerEvent;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public LocalDateTime getDecisionTime() { return decisionTime; }
    public void setDecisionTime(LocalDateTime decisionTime) { this.decisionTime = decisionTime; }
    public String getMonitorMode() { return monitorMode; }
    public void setMonitorMode(String monitorMode) { this.monitorMode = monitorMode; }
    public Boolean getShouldNotify() { return shouldNotify; }
    public void setShouldNotify(Boolean shouldNotify) { this.shouldNotify = shouldNotify; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
