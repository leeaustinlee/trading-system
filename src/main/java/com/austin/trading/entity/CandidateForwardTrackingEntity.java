package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_forward_tracking")
public class CandidateForwardTrackingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate tradingDate;
    private String stockId;
    private String stockName;
    private String finalDecision;
    private BigDecimal finalScore;
    private String grade;
    private String primaryStrategy;
    private String gateName;
    private BigDecimal entryPriceAtDecision;
    private BigDecimal t1CloseReturnPct;
    private BigDecimal t3CloseReturnPct;
    private BigDecimal t5CloseReturnPct;
    private BigDecimal t10CloseReturnPct;
    private BigDecimal mfePct;
    private BigDecimal maePct;
    private Boolean hitStop;
    private Boolean hitTarget;
    private BigDecimal benchmarkReturnPct;
    private BigDecimal relativeReturnPct;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getStockId() { return stockId; }
    public void setStockId(String stockId) { this.stockId = stockId; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getFinalDecision() { return finalDecision; }
    public void setFinalDecision(String finalDecision) { this.finalDecision = finalDecision; }
    public BigDecimal getFinalScore() { return finalScore; }
    public void setFinalScore(BigDecimal finalScore) { this.finalScore = finalScore; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getPrimaryStrategy() { return primaryStrategy; }
    public void setPrimaryStrategy(String primaryStrategy) { this.primaryStrategy = primaryStrategy; }
    public String getGateName() { return gateName; }
    public void setGateName(String gateName) { this.gateName = gateName; }
    public BigDecimal getEntryPriceAtDecision() { return entryPriceAtDecision; }
    public void setEntryPriceAtDecision(BigDecimal entryPriceAtDecision) { this.entryPriceAtDecision = entryPriceAtDecision; }
    public BigDecimal getT1CloseReturnPct() { return t1CloseReturnPct; }
    public void setT1CloseReturnPct(BigDecimal t1CloseReturnPct) { this.t1CloseReturnPct = t1CloseReturnPct; }
    public BigDecimal getT3CloseReturnPct() { return t3CloseReturnPct; }
    public void setT3CloseReturnPct(BigDecimal t3CloseReturnPct) { this.t3CloseReturnPct = t3CloseReturnPct; }
    public BigDecimal getT5CloseReturnPct() { return t5CloseReturnPct; }
    public void setT5CloseReturnPct(BigDecimal t5CloseReturnPct) { this.t5CloseReturnPct = t5CloseReturnPct; }
    public BigDecimal getT10CloseReturnPct() { return t10CloseReturnPct; }
    public void setT10CloseReturnPct(BigDecimal t10CloseReturnPct) { this.t10CloseReturnPct = t10CloseReturnPct; }
    public BigDecimal getMfePct() { return mfePct; }
    public void setMfePct(BigDecimal mfePct) { this.mfePct = mfePct; }
    public BigDecimal getMaePct() { return maePct; }
    public void setMaePct(BigDecimal maePct) { this.maePct = maePct; }
    public Boolean getHitStop() { return hitStop; }
    public void setHitStop(Boolean hitStop) { this.hitStop = hitStop; }
    public Boolean getHitTarget() { return hitTarget; }
    public void setHitTarget(Boolean hitTarget) { this.hitTarget = hitTarget; }
    public BigDecimal getBenchmarkReturnPct() { return benchmarkReturnPct; }
    public void setBenchmarkReturnPct(BigDecimal benchmarkReturnPct) { this.benchmarkReturnPct = benchmarkReturnPct; }
    public BigDecimal getRelativeReturnPct() { return relativeReturnPct; }
    public void setRelativeReturnPct(BigDecimal relativeReturnPct) { this.relativeReturnPct = relativeReturnPct; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
