package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_evaluation")
public class StockEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_profile", length = 40)
    private String stockProfile;

    @Column(name = "valuation_mode", length = 40)
    private String valuationMode;

    @Column(name = "entry_price_zone", length = 100)
    private String entryPriceZone;

    @Column(name = "stop_loss_price", precision = 12, scale = 4)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit_1", precision = 12, scale = 4)
    private BigDecimal takeProfit1;

    @Column(name = "take_profit_2", precision = 12, scale = 4)
    private BigDecimal takeProfit2;

    @Column(name = "risk_reward_ratio", precision = 8, scale = 4)
    private BigDecimal riskRewardRatio;

    @Column(name = "include_in_final_plan")
    private Boolean includeInFinalPlan;

    // ── 評分欄位（Phase 1 補入）────────────────────────────────────────────

    /** Java 結構評分 0~10，由 StockEvaluationEngine 計算 */
    @Column(name = "java_structure_score", precision = 6, scale = 3)
    private BigDecimal javaStructureScore;

    /** Claude 研究評分 0~10，由 Claude 回填 */
    @Column(name = "claude_score", precision = 6, scale = 3)
    private BigDecimal claudeScore;

    /** Claude 研究信心度 0~1 */
    @Column(name = "claude_confidence", precision = 4, scale = 2)
    private BigDecimal claudeConfidence;

    /** Claude 研究核心論述 */
    @Column(name = "claude_thesis", columnDefinition = "TEXT")
    private String claudeThesis;

    /** Claude 風險標誌（JSON array of strings） */
    @Column(name = "claude_risk_flags", columnDefinition = "json")
    private String claudeRiskFlags;

    /** Codex 審核評分 0~10（可選，scoring.enable_codex_review=true 時使用） */
    @Column(name = "codex_score", precision = 6, scale = 3)
    private BigDecimal codexScore;

    /** Codex 審核信心度 0~1 */
    @Column(name = "codex_confidence", precision = 4, scale = 2)
    private BigDecimal codexConfidence;

    /** Codex 提出的問題（JSON array of strings） */
    @Column(name = "codex_review_issues", columnDefinition = "json")
    private String codexReviewIssues;

    /** 加權後 AI 綜合分 = WeightedScoringEngine 計算 */
    @Column(name = "ai_weighted_score", precision = 6, scale = 3)
    private BigDecimal aiWeightedScore;

    /** 最終排序分：若被 veto 則為 0 */
    @Column(name = "final_rank_score", precision = 6, scale = 3)
    private BigDecimal finalRankScore;

    /** 各 AI 分數的標準差（分歧度指標） */
    @Column(name = "score_dispersion", precision = 6, scale = 3)
    private BigDecimal scoreDispersion;

    /** 是否被 VetoEngine 淘汰 */
    @Column(name = "is_vetoed")
    private Boolean isVetoed;

    /** 淘汰原因清單（JSON array，如 ["MARKET_GRADE_C","RR_BELOW_MIN"]） */
    @Column(name = "veto_reasons_json", columnDefinition = "json")
    private String vetoReasonsJson;

    /** 候選股排名（1=最優先） */
    @Column(name = "ranking_order")
    private Integer rankingOrder;

    /** 評分版本號（對應 score_config.scoring.version） */
    @Column(name = "score_version", length = 20)
    private String scoreVersion;

    /** 題材標籤（從 stock_theme_mapping 或 theme_snapshot 取得） */
    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    /** Java 硬性淘汰標誌（JSON，DEBUG 用） */
    @Column(name = "java_veto_flags", columnDefinition = "json")
    private String javaVetoFlags;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockProfile() { return stockProfile; }
    public void setStockProfile(String stockProfile) { this.stockProfile = stockProfile; }
    public String getValuationMode() { return valuationMode; }
    public void setValuationMode(String valuationMode) { this.valuationMode = valuationMode; }
    public String getEntryPriceZone() { return entryPriceZone; }
    public void setEntryPriceZone(String entryPriceZone) { this.entryPriceZone = entryPriceZone; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; }
    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }
    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }
    public BigDecimal getRiskRewardRatio() { return riskRewardRatio; }
    public void setRiskRewardRatio(BigDecimal riskRewardRatio) { this.riskRewardRatio = riskRewardRatio; }
    public Boolean getIncludeInFinalPlan() { return includeInFinalPlan; }
    public void setIncludeInFinalPlan(Boolean includeInFinalPlan) { this.includeInFinalPlan = includeInFinalPlan; }
    public BigDecimal getJavaStructureScore() { return javaStructureScore; }
    public void setJavaStructureScore(BigDecimal javaStructureScore) { this.javaStructureScore = javaStructureScore; }
    public BigDecimal getClaudeScore() { return claudeScore; }
    public void setClaudeScore(BigDecimal claudeScore) { this.claudeScore = claudeScore; }
    public BigDecimal getClaudeConfidence() { return claudeConfidence; }
    public void setClaudeConfidence(BigDecimal claudeConfidence) { this.claudeConfidence = claudeConfidence; }
    public String getClaudeThesis() { return claudeThesis; }
    public void setClaudeThesis(String claudeThesis) { this.claudeThesis = claudeThesis; }
    public String getClaudeRiskFlags() { return claudeRiskFlags; }
    public void setClaudeRiskFlags(String claudeRiskFlags) { this.claudeRiskFlags = claudeRiskFlags; }
    public BigDecimal getCodexScore() { return codexScore; }
    public void setCodexScore(BigDecimal codexScore) { this.codexScore = codexScore; }
    public BigDecimal getCodexConfidence() { return codexConfidence; }
    public void setCodexConfidence(BigDecimal codexConfidence) { this.codexConfidence = codexConfidence; }
    public String getCodexReviewIssues() { return codexReviewIssues; }
    public void setCodexReviewIssues(String codexReviewIssues) { this.codexReviewIssues = codexReviewIssues; }
    public BigDecimal getAiWeightedScore() { return aiWeightedScore; }
    public void setAiWeightedScore(BigDecimal aiWeightedScore) { this.aiWeightedScore = aiWeightedScore; }
    public BigDecimal getFinalRankScore() { return finalRankScore; }
    public void setFinalRankScore(BigDecimal finalRankScore) { this.finalRankScore = finalRankScore; }
    public BigDecimal getScoreDispersion() { return scoreDispersion; }
    public void setScoreDispersion(BigDecimal scoreDispersion) { this.scoreDispersion = scoreDispersion; }
    public Boolean getIsVetoed() { return isVetoed; }
    public void setIsVetoed(Boolean isVetoed) { this.isVetoed = isVetoed; }
    public String getVetoReasonsJson() { return vetoReasonsJson; }
    public void setVetoReasonsJson(String vetoReasonsJson) { this.vetoReasonsJson = vetoReasonsJson; }
    public Integer getRankingOrder() { return rankingOrder; }
    public void setRankingOrder(Integer rankingOrder) { this.rankingOrder = rankingOrder; }
    public String getScoreVersion() { return scoreVersion; }
    public void setScoreVersion(String scoreVersion) { this.scoreVersion = scoreVersion; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getJavaVetoFlags() { return javaVetoFlags; }
    public void setJavaVetoFlags(String javaVetoFlags) { this.javaVetoFlags = javaVetoFlags; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
