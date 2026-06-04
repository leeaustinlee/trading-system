package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name="stop_washout_outcome", indexes={@Index(name="idx_washout_struct_log", columnList="structural_exit_log_id"), @Index(name="idx_washout_symbol_signal", columnList="symbol,exit_signal_at"), @Index(name="idx_washout_label", columnList="outcome_label")})
public class StopWashoutOutcomeEntity {
    public static final String BASIS_SOURCE_EXIT = "SOURCE_EXIT";
    public static final String BASIS_ARBITER_EXIT_SHADOW = "ARBITER_EXIT_SHADOW";

    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="structural_exit_log_id", nullable=false) private Long structuralExitLogId;
    @Column(name="symbol", nullable=false, length=20) private String symbol;
    @Column(name="exit_signal_at", nullable=false) private LocalDateTime exitSignalAt;
    @Column(name="signal_tier", length=40) private String signalTier;
    @Column(name="outcome_basis", length=40) private String outcomeBasis;
    @Column(name="source_decision_status", length=40) private String sourceDecisionStatus;
    @Column(name="signal_price", precision=12, scale=4) private BigDecimal signalPrice;
    @Column(name="t1_max_return_pct", precision=10, scale=4) private BigDecimal t1MaxReturnPct;
    @Column(name="t3_max_return_pct", precision=10, scale=4) private BigDecimal t3MaxReturnPct;
    @Column(name="t5_max_return_pct", precision=10, scale=4) private BigDecimal t5MaxReturnPct;
    @Column(name="t10_max_return_pct", precision=10, scale=4) private BigDecimal t10MaxReturnPct;
    @Column(name="high_1d", precision=12, scale=4) private BigDecimal high1d;
    @Column(name="high_3d", precision=12, scale=4) private BigDecimal high3d;
    @Column(name="high_5d", precision=12, scale=4) private BigDecimal high5d;
    @Column(name="high_10d", precision=12, scale=4) private BigDecimal high10d;
    @Column(name="new_high_3_10d") private Boolean newHigh3To10d;
    @Column(name="outcome_label", length=40) private String outcomeLabel;
    @Column(name="evaluated_at", nullable=false) private LocalDateTime evaluatedAt = LocalDateTime.now();
    public Long getId(){return id;} public Long getStructuralExitLogId(){return structuralExitLogId;} public void setStructuralExitLogId(Long v){structuralExitLogId=v;} public String getSymbol(){return symbol;} public void setSymbol(String v){symbol=v;} public LocalDateTime getExitSignalAt(){return exitSignalAt;} public void setExitSignalAt(LocalDateTime v){exitSignalAt=v;} public String getSignalTier(){return signalTier;} public void setSignalTier(String v){signalTier=v;} public String getOutcomeBasis(){return outcomeBasis;} public void setOutcomeBasis(String v){outcomeBasis=v;} public String getSourceDecisionStatus(){return sourceDecisionStatus;} public void setSourceDecisionStatus(String v){sourceDecisionStatus=v;} public BigDecimal getSignalPrice(){return signalPrice;} public void setSignalPrice(BigDecimal v){signalPrice=v;} public BigDecimal getT1MaxReturnPct(){return t1MaxReturnPct;} public void setT1MaxReturnPct(BigDecimal v){t1MaxReturnPct=v;} public BigDecimal getT3MaxReturnPct(){return t3MaxReturnPct;} public void setT3MaxReturnPct(BigDecimal v){t3MaxReturnPct=v;} public BigDecimal getT5MaxReturnPct(){return t5MaxReturnPct;} public void setT5MaxReturnPct(BigDecimal v){t5MaxReturnPct=v;} public BigDecimal getT10MaxReturnPct(){return t10MaxReturnPct;} public void setT10MaxReturnPct(BigDecimal v){t10MaxReturnPct=v;} public BigDecimal getHigh1d(){return high1d;} public void setHigh1d(BigDecimal v){high1d=v;} public BigDecimal getHigh3d(){return high3d;} public void setHigh3d(BigDecimal v){high3d=v;} public BigDecimal getHigh5d(){return high5d;} public void setHigh5d(BigDecimal v){high5d=v;} public BigDecimal getHigh10d(){return high10d;} public void setHigh10d(BigDecimal v){high10d=v;} public Boolean getNewHigh3To10d(){return newHigh3To10d;} public void setNewHigh3To10d(Boolean v){newHigh3To10d=v;} public String getOutcomeLabel(){return outcomeLabel;} public void setOutcomeLabel(String v){outcomeLabel=v;} public LocalDateTime getEvaluatedAt(){return evaluatedAt;} public void setEvaluatedAt(LocalDateTime v){evaluatedAt=v;}
}
