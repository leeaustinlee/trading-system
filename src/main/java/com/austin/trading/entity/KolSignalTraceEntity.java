package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "kol_signal_trace")
public class KolSignalTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_id")
    private Long signalId;

    @Column(name = "trace_stage", nullable = false, length = 60)
    private String traceStage;

    @Column(name = "trace_action", nullable = false, length = 80)
    private String traceAction;

    @Column(name = "detail_json", columnDefinition = "json")
    private String detailJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Long getSignalId() { return signalId; }
    public void setSignalId(Long signalId) { this.signalId = signalId; }
    public String getTraceStage() { return traceStage; }
    public void setTraceStage(String traceStage) { this.traceStage = traceStage; }
    public String getTraceAction() { return traceAction; }
    public void setTraceAction(String traceAction) { this.traceAction = traceAction; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
