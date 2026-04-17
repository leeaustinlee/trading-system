package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "hourly_gate_decision")
public class HourlyGateDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "gate_time", nullable = false)
    private LocalTime gateTime;

    @Column(name = "hourly_gate", nullable = false, length = 20)
    private String hourlyGate;

    @Column(name = "should_run_5m_monitor")
    private Boolean shouldRun5mMonitor;

    @Column(name = "trigger_event", length = 50)
    private String triggerEvent;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public LocalTime getGateTime() { return gateTime; }
    public void setGateTime(LocalTime gateTime) { this.gateTime = gateTime; }
    public String getHourlyGate() { return hourlyGate; }
    public void setHourlyGate(String hourlyGate) { this.hourlyGate = hourlyGate; }
    public Boolean getShouldRun5mMonitor() { return shouldRun5mMonitor; }
    public void setShouldRun5mMonitor(Boolean shouldRun5mMonitor) { this.shouldRun5mMonitor = shouldRun5mMonitor; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
