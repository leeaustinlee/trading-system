package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stop_outcome_ledger",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stop_outcome_paper_trade", columnNames = "paper_trade_id")
        },
        indexes = {
                @Index(name = "idx_stop_outcome_symbol_exit_date", columnList = "symbol,exit_date"),
                @Index(name = "idx_stop_outcome_label", columnList = "outcome_label"),
                @Index(name = "idx_stop_outcome_exit_reason", columnList = "exit_reason")
        })
public class StopOutcomeLedgerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_trade_id", nullable = false)
    private Long paperTradeId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "exit_date", nullable = false)
    private LocalDate exitDate;

    @Column(name = "exit_reason", nullable = false, length = 40)
    private String exitReason;

    @Column(name = "exit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "entry_price", precision = 12, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "strategy_type", length = 30)
    private String strategyType;

    @Column(name = "return_1d_after_exit", precision = 8, scale = 4)
    private BigDecimal return1dAfterExit;

    @Column(name = "return_3d_after_exit", precision = 8, scale = 4)
    private BigDecimal return3dAfterExit;

    @Column(name = "return_5d_after_exit", precision = 8, scale = 4)
    private BigDecimal return5dAfterExit;

    @Column(name = "return_10d_after_exit", precision = 8, scale = 4)
    private BigDecimal return10dAfterExit;

    @Column(name = "max_return_after_exit", precision = 8, scale = 4)
    private BigDecimal maxReturnAfterExit;

    @Column(name = "min_return_after_exit", precision = 8, scale = 4)
    private BigDecimal minReturnAfterExit;

    @Column(name = "outcome_label", nullable = false, length = 40)
    private String outcomeLabel = "PENDING_DATA";

    @Column(name = "evidence_json", columnDefinition = "json")
    private String evidenceJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public Long getPaperTradeId() { return paperTradeId; }
    public void setPaperTradeId(Long paperTradeId) { this.paperTradeId = paperTradeId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate exitDate) { this.exitDate = exitDate; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public BigDecimal getReturn1dAfterExit() { return return1dAfterExit; }
    public void setReturn1dAfterExit(BigDecimal return1dAfterExit) { this.return1dAfterExit = return1dAfterExit; }
    public BigDecimal getReturn3dAfterExit() { return return3dAfterExit; }
    public void setReturn3dAfterExit(BigDecimal return3dAfterExit) { this.return3dAfterExit = return3dAfterExit; }
    public BigDecimal getReturn5dAfterExit() { return return5dAfterExit; }
    public void setReturn5dAfterExit(BigDecimal return5dAfterExit) { this.return5dAfterExit = return5dAfterExit; }
    public BigDecimal getReturn10dAfterExit() { return return10dAfterExit; }
    public void setReturn10dAfterExit(BigDecimal return10dAfterExit) { this.return10dAfterExit = return10dAfterExit; }
    public BigDecimal getMaxReturnAfterExit() { return maxReturnAfterExit; }
    public void setMaxReturnAfterExit(BigDecimal maxReturnAfterExit) { this.maxReturnAfterExit = maxReturnAfterExit; }
    public BigDecimal getMinReturnAfterExit() { return minReturnAfterExit; }
    public void setMinReturnAfterExit(BigDecimal minReturnAfterExit) { this.minReturnAfterExit = minReturnAfterExit; }
    public String getOutcomeLabel() { return outcomeLabel; }
    public void setOutcomeLabel(String outcomeLabel) { this.outcomeLabel = outcomeLabel; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
