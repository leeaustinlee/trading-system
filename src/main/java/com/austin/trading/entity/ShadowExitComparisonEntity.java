package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shadow_exit_comparison",
        indexes = {
                @Index(name = "idx_shadow_exit_ref", columnList = "trade_ref_type,trade_ref_id"),
                @Index(name = "idx_shadow_exit_symbol", columnList = "symbol"),
                @Index(name = "idx_shadow_exit_evaluated", columnList = "evaluated_at")
        })
public class ShadowExitComparisonEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trade_ref_type", nullable = false, length = 20)
    private String tradeRefType;
    @Column(name = "trade_ref_id", nullable = false)
    private Long tradeRefId;
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;
    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt = LocalDateTime.now();
    @Column(name = "current_rule_action", length = 40)
    private String currentRuleAction;
    @Column(name = "current_rule_exit_price", precision = 12, scale = 4)
    private BigDecimal currentRuleExitPrice;
    @Column(name = "ma5_action", length = 40)
    private String ma5Action;
    @Column(name = "ma5_price", precision = 12, scale = 4)
    private BigDecimal ma5Price;
    @Column(name = "ma10_action", length = 40)
    private String ma10Action;
    @Column(name = "ma10_price", precision = 12, scale = 4)
    private BigDecimal ma10Price;
    @Column(name = "prev_low_action", length = 40)
    private String prevLowAction;
    @Column(name = "prev_low_price", precision = 12, scale = 4)
    private BigDecimal prevLowPrice;
    @Column(name = "atr_action", length = 40)
    private String atrAction;
    @Column(name = "atr_price", precision = 12, scale = 4)
    private BigDecimal atrPrice;
    @Column(name = "hybrid_action", length = 40)
    private String hybridAction;
    @Column(name = "hybrid_price", precision = 12, scale = 4)
    private BigDecimal hybridPrice;
    @Column(name = "hypothetical_return_json", columnDefinition = "json")
    private String hypotheticalReturnJson;
    @Column(name = "data_gaps", columnDefinition = "json")
    private String dataGaps;

    public Long getId() { return id; }
    public String getTradeRefType() { return tradeRefType; }
    public void setTradeRefType(String tradeRefType) { this.tradeRefType = tradeRefType; }
    public Long getTradeRefId() { return tradeRefId; }
    public void setTradeRefId(Long tradeRefId) { this.tradeRefId = tradeRefId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
    public String getCurrentRuleAction() { return currentRuleAction; }
    public void setCurrentRuleAction(String currentRuleAction) { this.currentRuleAction = currentRuleAction; }
    public BigDecimal getCurrentRuleExitPrice() { return currentRuleExitPrice; }
    public void setCurrentRuleExitPrice(BigDecimal currentRuleExitPrice) { this.currentRuleExitPrice = currentRuleExitPrice; }
    public String getMa5Action() { return ma5Action; }
    public void setMa5Action(String ma5Action) { this.ma5Action = ma5Action; }
    public BigDecimal getMa5Price() { return ma5Price; }
    public void setMa5Price(BigDecimal ma5Price) { this.ma5Price = ma5Price; }
    public String getMa10Action() { return ma10Action; }
    public void setMa10Action(String ma10Action) { this.ma10Action = ma10Action; }
    public BigDecimal getMa10Price() { return ma10Price; }
    public void setMa10Price(BigDecimal ma10Price) { this.ma10Price = ma10Price; }
    public String getPrevLowAction() { return prevLowAction; }
    public void setPrevLowAction(String prevLowAction) { this.prevLowAction = prevLowAction; }
    public BigDecimal getPrevLowPrice() { return prevLowPrice; }
    public void setPrevLowPrice(BigDecimal prevLowPrice) { this.prevLowPrice = prevLowPrice; }
    public String getAtrAction() { return atrAction; }
    public void setAtrAction(String atrAction) { this.atrAction = atrAction; }
    public BigDecimal getAtrPrice() { return atrPrice; }
    public void setAtrPrice(BigDecimal atrPrice) { this.atrPrice = atrPrice; }
    public String getHybridAction() { return hybridAction; }
    public void setHybridAction(String hybridAction) { this.hybridAction = hybridAction; }
    public BigDecimal getHybridPrice() { return hybridPrice; }
    public void setHybridPrice(BigDecimal hybridPrice) { this.hybridPrice = hybridPrice; }
    public String getHypotheticalReturnJson() { return hypotheticalReturnJson; }
    public void setHypotheticalReturnJson(String hypotheticalReturnJson) { this.hypotheticalReturnJson = hypotheticalReturnJson; }
    public String getDataGaps() { return dataGaps; }
    public void setDataGaps(String dataGaps) { this.dataGaps = dataGaps; }
}
