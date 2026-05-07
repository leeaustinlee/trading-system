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
}
