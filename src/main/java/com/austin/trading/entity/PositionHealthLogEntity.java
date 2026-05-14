package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "position_health_log",
        indexes = {
                @Index(name = "idx_position_health_position", columnList = "position_id"),
                @Index(name = "idx_position_health_symbol", columnList = "symbol"),
                @Index(name = "idx_position_health_evaluated", columnList = "evaluated_at")
        })
public class PositionHealthLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "position_id", nullable = false)
    private Long positionId;
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;
    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt = LocalDateTime.now();
    @Column(name = "current_price", precision = 12, scale = 4)
    private BigDecimal currentPrice;
    @Column(name = "health_score")
    private Integer healthScore;
    @Column(name = "structure_status", length = 40)
    private String structureStatus;
    @Column(name = "volume_status", length = 40)
    private String volumeStatus;
    @Column(name = "relative_strength_status", length = 40)
    private String relativeStrengthStatus;
    @Column(name = "chip_status", length = 40)
    private String chipStatus;
    @Column(name = "exit_tier", length = 40)
    private String exitTier;
    @Column(name = "reasons_json", columnDefinition = "json")
    private String reasonsJson;
    @Column(name = "data_gaps_json", columnDefinition = "json")
    private String dataGapsJson;

    public Long getId() { return id; }
    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }
    public Integer getHealthScore() { return healthScore; }
    public void setHealthScore(Integer healthScore) { this.healthScore = healthScore; }
    public String getStructureStatus() { return structureStatus; }
    public void setStructureStatus(String structureStatus) { this.structureStatus = structureStatus; }
    public String getVolumeStatus() { return volumeStatus; }
    public void setVolumeStatus(String volumeStatus) { this.volumeStatus = volumeStatus; }
    public String getRelativeStrengthStatus() { return relativeStrengthStatus; }
    public void setRelativeStrengthStatus(String relativeStrengthStatus) { this.relativeStrengthStatus = relativeStrengthStatus; }
    public String getChipStatus() { return chipStatus; }
    public void setChipStatus(String chipStatus) { this.chipStatus = chipStatus; }
    public String getExitTier() { return exitTier; }
    public void setExitTier(String exitTier) { this.exitTier = exitTier; }
    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String reasonsJson) { this.reasonsJson = reasonsJson; }
    public String getDataGapsJson() { return dataGapsJson; }
    public void setDataGapsJson(String dataGapsJson) { this.dataGapsJson = dataGapsJson; }
}
