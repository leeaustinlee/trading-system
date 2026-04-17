package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_research_log")
public class AiResearchLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "research_type", nullable = false, length = 50)
    private String researchType;

    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "prompt_summary", length = 500)
    private String promptSummary;

    @Column(name = "research_result", columnDefinition = "LONGTEXT")
    private String researchResult;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getResearchType() { return researchType; }
    public void setResearchType(String researchType) { this.researchType = researchType; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getPromptSummary() { return promptSummary; }
    public void setPromptSummary(String promptSummary) { this.promptSummary = promptSummary; }
    public String getResearchResult() { return researchResult; }
    public void setResearchResult(String researchResult) { this.researchResult = researchResult; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(Integer tokensUsed) { this.tokensUsed = tokensUsed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
