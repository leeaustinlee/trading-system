package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "structural_exit_decision_log", indexes = {
        @Index(name = "idx_struct_exit_ref", columnList = "trade_ref_type,trade_ref_id"),
        @Index(name = "idx_struct_exit_symbol_date", columnList = "symbol,evaluation_date"),
        @Index(name = "idx_struct_exit_review", columnList = "source_review_log_id,mode"),
        @Index(name = "idx_struct_exit_tier_date", columnList = "arbiter_tier,evaluated_at")
})
public class StructuralExitDecisionLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="trade_ref_type", nullable=false, length=20) private String tradeRefType;
    @Column(name="trade_ref_id", nullable=false) private Long tradeRefId;
    @Column(name="symbol", nullable=false, length=20) private String symbol;
    @Column(name="evaluated_at", nullable=false) private LocalDateTime evaluatedAt = LocalDateTime.now();
    @Column(name="evaluation_date") private LocalDate evaluationDate;
    @Column(name="source_review_log_id") private Long sourceReviewLogId;
    @Column(name="review_date") private LocalDate reviewDate;
    @Column(name="mode", nullable=false, length=20) private String mode = "LIVE";
    @Column(name="source_decision_status", length=40) private String sourceDecisionStatus;
    @Column(name="source_exit_reason", length=512) private String sourceExitReason;
    @Column(name="arbiter_tier", nullable=false, length=40) private String arbiterTier;
    @Column(name="arbiter_reason", length=512) private String arbiterReason;
    @Column(name="risk_block") private Boolean riskBlock = false;
    @Column(name="manual_confirm_required", nullable=false) private Boolean manualConfirmRequired = true;
    @Column(name="auto_sell_enabled", nullable=false) private Boolean autoSellEnabled = false;
    @Column(name="theme_state", length=40) private String themeState;
    @Column(name="theme_stage", length=40) private String themeStage;
    @Column(name="theme_rank") private Integer themeRank;
    @Column(name="theme_score", precision=8, scale=4) private BigDecimal themeScore;
    @Column(name="mainstream_theme") private Boolean mainstreamTheme;
    @Column(name="structure_state", length=40) private String structureState;
    @Column(name="health_score") private Integer healthScore;
    @Column(name="structure_status", length=80) private String structureStatus;
    @Column(name="volume_status", length=80) private String volumeStatus;
    @Column(name="relative_strength_status", length=80) private String relativeStrengthStatus;
    @Column(name="chip_status", length=80) private String chipStatus;
    @Column(name="price_state", length=40) private String priceState;
    @Column(name="current_price", precision=12, scale=4) private BigDecimal currentPrice;
    @Column(name="entry_price", precision=12, scale=4) private BigDecimal entryPrice;
    @Column(name="hard_stop_price", precision=12, scale=4) private BigDecimal hardStopPrice;
    @Column(name="trailing_stop_price", precision=12, scale=4) private BigDecimal trailingStopPrice;
    @Column(name="dynamic_stop_price", precision=12, scale=4) private BigDecimal dynamicStopPrice;
    @Column(name="ma5", precision=12, scale=4) private BigDecimal ma5;
    @Column(name="ma10", precision=12, scale=4) private BigDecimal ma10;
    @Column(name="ma20", precision=12, scale=4) private BigDecimal ma20;
    @Column(name="previous_low", precision=12, scale=4) private BigDecimal previousLow;
    @Column(name="recent_high", precision=12, scale=4) private BigDecimal recentHigh;
    @Column(name="atr", precision=12, scale=4) private BigDecimal atr;
    @Column(name="price_trigger_json", columnDefinition="json") private String priceTriggerJson;
    @Column(name="layer_votes_json", columnDefinition="json") private String layerVotesJson;
    @Column(name="data_gaps_json", columnDefinition="json") private String dataGapsJson;
    @Column(name="reason_json", columnDefinition="json") private String reasonJson;
    @Column(name="audit_tags_json", columnDefinition="json") private String auditTagsJson;

    public static Builder shadowBuilder(){return new Builder();}
    public static class Builder { private final StructuralExitDecisionLogEntity e = new StructuralExitDecisionLogEntity();
        public Builder tradeRefType(String v){e.setTradeRefType(v);return this;} public Builder tradeRefId(Long v){e.setTradeRefId(v);return this;} public Builder symbol(String v){e.setSymbol(v);return this;} public Builder evaluatedAt(LocalDateTime v){e.setEvaluatedAt(v);return this;}
        public Builder sourceDecisionStatus(String v){e.setSourceDecisionStatus(v);return this;} public Builder arbiterTier(String v){e.setArbiterTier(v);return this;} public Builder arbiterReason(String v){e.setArbiterReason(v);return this;} public Builder manualConfirmRequired(boolean v){e.setManualConfirmRequired(v);return this;} public Builder autoSellEnabled(boolean v){e.setAutoSellEnabled(v);return this;} public Builder dataGaps(List<String> v){return this;} public StructuralExitDecisionLogEntity build(){return e;}}
    public Long getId(){return id;} public void setId(Long id){this.id=id;} public String getTradeRefType(){return tradeRefType;} public void setTradeRefType(String v){tradeRefType=v;} public Long getTradeRefId(){return tradeRefId;} public void setTradeRefId(Long v){tradeRefId=v;} public String getSymbol(){return symbol;} public void setSymbol(String v){symbol=v;}
    public LocalDateTime getEvaluatedAt(){return evaluatedAt;} public void setEvaluatedAt(LocalDateTime v){evaluatedAt=v;} public LocalDate getEvaluationDate(){return evaluationDate;} public void setEvaluationDate(LocalDate v){evaluationDate=v;}
    public Long getSourceReviewLogId(){return sourceReviewLogId;} public void setSourceReviewLogId(Long v){sourceReviewLogId=v;} public LocalDate getReviewDate(){return reviewDate;} public void setReviewDate(LocalDate v){reviewDate=v;} public String getMode(){return mode;} public void setMode(String v){mode=v;}
    public String getSourceDecisionStatus(){return sourceDecisionStatus;} public void setSourceDecisionStatus(String v){sourceDecisionStatus=v;} public String getSourceExitReason(){return sourceExitReason;} public void setSourceExitReason(String v){sourceExitReason=v;} public String getArbiterTier(){return arbiterTier;} public void setArbiterTier(String v){arbiterTier=v;} public String getArbiterReason(){return arbiterReason;} public void setArbiterReason(String v){arbiterReason=v;} public Boolean getRiskBlock(){return riskBlock;} public void setRiskBlock(Boolean v){riskBlock=v;}
    public Boolean getManualConfirmRequired(){return manualConfirmRequired;} public void setManualConfirmRequired(Boolean v){manualConfirmRequired=v;} public Boolean getAutoSellEnabled(){return autoSellEnabled;} public void setAutoSellEnabled(Boolean v){autoSellEnabled=v;}
    public String getThemeState(){return themeState;} public void setThemeState(String v){themeState=v;} public String getThemeStage(){return themeStage;} public void setThemeStage(String v){themeStage=v;} public Integer getThemeRank(){return themeRank;} public void setThemeRank(Integer v){themeRank=v;} public BigDecimal getThemeScore(){return themeScore;} public void setThemeScore(BigDecimal v){themeScore=v;} public Boolean getMainstreamTheme(){return mainstreamTheme;} public void setMainstreamTheme(Boolean v){mainstreamTheme=v;}
    public String getStructureState(){return structureState;} public void setStructureState(String v){structureState=v;} public Integer getHealthScore(){return healthScore;} public void setHealthScore(Integer v){healthScore=v;} public String getStructureStatus(){return structureStatus;} public void setStructureStatus(String v){structureStatus=v;} public String getVolumeStatus(){return volumeStatus;} public void setVolumeStatus(String v){volumeStatus=v;} public String getRelativeStrengthStatus(){return relativeStrengthStatus;} public void setRelativeStrengthStatus(String v){relativeStrengthStatus=v;} public String getChipStatus(){return chipStatus;} public void setChipStatus(String v){chipStatus=v;}
    public String getPriceState(){return priceState;} public void setPriceState(String v){priceState=v;} public BigDecimal getCurrentPrice(){return currentPrice;} public void setCurrentPrice(BigDecimal v){currentPrice=v;} public BigDecimal getEntryPrice(){return entryPrice;} public void setEntryPrice(BigDecimal v){entryPrice=v;} public BigDecimal getHardStopPrice(){return hardStopPrice;} public void setHardStopPrice(BigDecimal v){hardStopPrice=v;} public BigDecimal getTrailingStopPrice(){return trailingStopPrice;} public void setTrailingStopPrice(BigDecimal v){trailingStopPrice=v;} public BigDecimal getDynamicStopPrice(){return dynamicStopPrice;} public void setDynamicStopPrice(BigDecimal v){dynamicStopPrice=v;} public BigDecimal getMa5(){return ma5;} public void setMa5(BigDecimal v){ma5=v;} public BigDecimal getMa10(){return ma10;} public void setMa10(BigDecimal v){ma10=v;} public BigDecimal getMa20(){return ma20;} public void setMa20(BigDecimal v){ma20=v;} public BigDecimal getPreviousLow(){return previousLow;} public void setPreviousLow(BigDecimal v){previousLow=v;} public BigDecimal getRecentHigh(){return recentHigh;} public void setRecentHigh(BigDecimal v){recentHigh=v;} public BigDecimal getAtr(){return atr;} public void setAtr(BigDecimal v){atr=v;}
    public String getPriceTriggerJson(){return priceTriggerJson;} public void setPriceTriggerJson(String v){priceTriggerJson=v;} public String getLayerVotesJson(){return layerVotesJson;} public void setLayerVotesJson(String v){layerVotesJson=v;} public String getDataGapsJson(){return dataGapsJson;} public void setDataGapsJson(String v){dataGapsJson=v;} public String getReasonJson(){return reasonJson;} public void setReasonJson(String v){reasonJson=v;} public String getAuditTagsJson(){return auditTagsJson;} public void setAuditTagsJson(String v){auditTagsJson=v;}
}
