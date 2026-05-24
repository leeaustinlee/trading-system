package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "system_build_trace",
        indexes = {
                @Index(name = "idx_system_build_trace_date", columnList = "trading_date"),
                @Index(name = "idx_system_build_trace_type_date", columnList = "build_type, trading_date"),
                @Index(name = "idx_system_build_trace_status", columnList = "status")
        })
public class SystemBuildTraceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "build_type", nullable = false, length = 40) private String buildType;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "source_phase", length = 40) private String sourcePhase;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "status", nullable = false, length = 20) private String status;
    @Column(name = "deleted_count", nullable = false) private Integer deletedCount = 0;
    @Column(name = "inserted_count", nullable = false) private Integer insertedCount = 0;
    @Column(name = "updated_count", nullable = false) private Integer updatedCount = 0;
    @Column(name = "skipped_count", nullable = false) private Integer skippedCount = 0;
    @Column(name = "error_message", length = 2000) private String errorMessage;
    @Column(name = "safety_boundary_json", columnDefinition = "json") private String safetyBoundaryJson;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;

    public Long getId() { return id; }
    public String getBuildType() { return buildType; }
    public void setBuildType(String buildType) { this.buildType = buildType; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDeletedCount() { return deletedCount; }
    public void setDeletedCount(Integer deletedCount) { this.deletedCount = deletedCount; }
    public Integer getInsertedCount() { return insertedCount; }
    public void setInsertedCount(Integer insertedCount) { this.insertedCount = insertedCount; }
    public Integer getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(Integer updatedCount) { this.updatedCount = updatedCount; }
    public Integer getSkippedCount() { return skippedCount; }
    public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSafetyBoundaryJson() { return safetyBoundaryJson; }
    public void setSafetyBoundaryJson(String safetyBoundaryJson) { this.safetyBoundaryJson = safetyBoundaryJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
