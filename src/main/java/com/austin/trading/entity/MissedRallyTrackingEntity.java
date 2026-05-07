package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "missed_rally_tracking")
public class MissedRallyTrackingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate tradingDate;
    private String stockId;
    private String stockName;
    private String originalDecision;
    private String originalGrade;
    private BigDecimal originalScore;
    private String primaryStrategy;
    @Column(length = 1000) private String rejectReason;
    private String gateName;
    private BigDecimal currentPriceAtDecision;
    private BigDecimal nextDayHigh;
    private BigDecimal nextDayClose;
    private BigDecimal t3High;
    private BigDecimal t3Close;
    private BigDecimal t5High;
    private BigDecimal t5Close;
    private BigDecimal t10High;
    private BigDecimal t10Close;
    private BigDecimal maxReturnPct;
    private BigDecimal closeReturnPct;
    private BigDecimal mfePct;
    private BigDecimal maePct;
    private Boolean wouldHaveHitStop;
    private Boolean wouldHaveHitTarget;
    private Boolean missedRallyFlag;
    @Column(length = 1000) private String missedRallyReason;
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
    public String getOriginalDecision() { return originalDecision; }
    public void setOriginalDecision(String originalDecision) { this.originalDecision = originalDecision; }
    public String getOriginalGrade() { return originalGrade; }
    public void setOriginalGrade(String originalGrade) { this.originalGrade = originalGrade; }
    public BigDecimal getOriginalScore() { return originalScore; }
    public void setOriginalScore(BigDecimal originalScore) { this.originalScore = originalScore; }
    public String getPrimaryStrategy() { return primaryStrategy; }
    public void setPrimaryStrategy(String primaryStrategy) { this.primaryStrategy = primaryStrategy; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getGateName() { return gateName; }
    public void setGateName(String gateName) { this.gateName = gateName; }
    public BigDecimal getCurrentPriceAtDecision() { return currentPriceAtDecision; }
    public void setCurrentPriceAtDecision(BigDecimal currentPriceAtDecision) { this.currentPriceAtDecision = currentPriceAtDecision; }
    public BigDecimal getT5High() { return t5High; }
    public void setT5High(BigDecimal t5High) { this.t5High = t5High; }
    public BigDecimal getCloseReturnPct() { return closeReturnPct; }
    public void setCloseReturnPct(BigDecimal closeReturnPct) { this.closeReturnPct = closeReturnPct; }
    public BigDecimal getMfePct() { return mfePct; }
    public void setMfePct(BigDecimal mfePct) { this.mfePct = mfePct; }
    public BigDecimal getMaePct() { return maePct; }
    public void setMaePct(BigDecimal maePct) { this.maePct = maePct; }
    public BigDecimal getMaxReturnPct() { return maxReturnPct; }
    public void setMaxReturnPct(BigDecimal maxReturnPct) { this.maxReturnPct = maxReturnPct; }
    public Boolean getMissedRallyFlag() { return missedRallyFlag; }
    public void setMissedRallyFlag(Boolean missedRallyFlag) { this.missedRallyFlag = missedRallyFlag; }
    public String getMissedRallyReason() { return missedRallyReason; }
    public void setMissedRallyReason(String missedRallyReason) { this.missedRallyReason = missedRallyReason; }
}
