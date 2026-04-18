package com.austin.trading.service;

import com.austin.trading.dto.request.AiScoreUpdateRequest;
import com.austin.trading.dto.request.StockEvaluateRequest;
import com.austin.trading.dto.response.StockEvaluateResult;
import com.austin.trading.engine.StockEvaluationEngine;
import com.austin.trading.engine.WeightedScoringEngine;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.repository.StockEvaluationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(StockEvaluationService.class);

    private final StockEvaluationEngine     stockEvaluationEngine;
    private final WeightedScoringEngine     weightedScoringEngine;
    private final StockEvaluationRepository stockEvaluationRepository;
    private final ObjectMapper              objectMapper;

    public StockEvaluationService(
            StockEvaluationEngine stockEvaluationEngine,
            WeightedScoringEngine weightedScoringEngine,
            StockEvaluationRepository stockEvaluationRepository,
            ObjectMapper objectMapper
    ) {
        this.stockEvaluationEngine     = stockEvaluationEngine;
        this.weightedScoringEngine     = weightedScoringEngine;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.objectMapper              = objectMapper;
    }

    /**
     * 批次評估候選股並寫入 stock_evaluation 表。
     * 同一交易日同一 symbol 的舊紀錄會先刪除，再寫入最新評估結果。
     */
    public List<StockEvaluateResult> batchEvaluateAndSave(LocalDate tradingDate,
                                                          List<StockEvaluateRequest> requests) {
        List<StockEvaluateResult> results = new ArrayList<>();
        for (StockEvaluateRequest req : requests) {
            StockEvaluateResult result = stockEvaluationEngine.evaluate(req);
            saveOrUpdate(tradingDate, result);
            results.add(result);
        }
        return results;
    }

    /** 評估單一股票（不寫 DB，供即時 API 使用）。 */
    public StockEvaluateResult evaluate(StockEvaluateRequest request) {
        return stockEvaluationEngine.evaluate(request);
    }

    /** 取得指定日期所有評估紀錄。 */
    public List<StockEvaluationEntity> getByDate(LocalDate tradingDate) {
        return stockEvaluationRepository.findByTradingDate(tradingDate);
    }

    /**
     * 回填 AI 評分（Claude / Codex），並自動重算 ai_weighted_score 與 final_rank_score。
     * 若當日尚無評估紀錄，建立空殼紀錄再寫入。
     *
     * @return 更新後的 StockEvaluationEntity
     */
    @Transactional
    public StockEvaluationEntity updateAiScores(String symbol, AiScoreUpdateRequest req) {
        LocalDate date = req.tradingDate() != null ? req.tradingDate() : LocalDate.now();

        StockEvaluationEntity eval = stockEvaluationRepository
                .findByTradingDateAndSymbol(date, symbol)
                .orElseGet(() -> {
                    StockEvaluationEntity e = new StockEvaluationEntity();
                    e.setTradingDate(date);
                    e.setSymbol(symbol);
                    return e;
                });

        // 更新 Claude 欄位
        if (req.claudeScore()      != null) eval.setClaudeScore(req.claudeScore());
        if (req.claudeConfidence() != null) eval.setClaudeConfidence(req.claudeConfidence());
        if (req.claudeThesis()     != null) eval.setClaudeThesis(req.claudeThesis());
        if (req.claudeRiskFlags()  != null && !req.claudeRiskFlags().isEmpty()) {
            eval.setClaudeRiskFlags(toJson(req.claudeRiskFlags()));
        }

        // 更新 Codex 欄位
        if (req.codexScore()        != null) eval.setCodexScore(req.codexScore());
        if (req.codexConfidence()   != null) eval.setCodexConfidence(req.codexConfidence());
        if (req.codexReviewIssues() != null && !req.codexReviewIssues().isEmpty()) {
            eval.setCodexReviewIssues(toJson(req.codexReviewIssues()));
        }

        // 重算加權評分與最終排序分
        BigDecimal javaScore   = eval.getJavaStructureScore();
        BigDecimal claudeScore = eval.getClaudeScore();
        BigDecimal codexScore  = eval.getCodexScore();
        BigDecimal aiWeighted  = weightedScoringEngine.computeAiWeightedScore(javaScore, claudeScore, codexScore);
        boolean    isVetoed    = Boolean.TRUE.equals(eval.getIsVetoed());
        BigDecimal finalRank   = weightedScoringEngine.computeFinalRankScore(aiWeighted, isVetoed);

        eval.setAiWeightedScore(aiWeighted);
        eval.setFinalRankScore(finalRank);

        StockEvaluationEntity saved = stockEvaluationRepository.save(eval);
        log.info("[StockEvalService] AI score updated: symbol={}, date={}, java={}, claude={}, codex={}, finalRank={}",
                symbol, date, javaScore, claudeScore, codexScore, finalRank);
        return saved;
    }

    // ── 私有方法 ───────────────────────────────────────────────────────────────

    private void saveOrUpdate(LocalDate tradingDate, StockEvaluateResult result) {
        // 同日同 symbol 先刪除舊紀錄（避免重複）
        List<StockEvaluationEntity> existing = stockEvaluationRepository.findByTradingDate(tradingDate);
        existing.stream()
                .filter(e -> result.symbol().equals(e.getSymbol()))
                .forEach(stockEvaluationRepository::delete);

        StockEvaluationEntity entity = new StockEvaluationEntity();
        entity.setTradingDate(tradingDate);
        entity.setSymbol(result.symbol());
        entity.setValuationMode(result.valuationMode());
        entity.setEntryPriceZone(result.entryPriceZone());
        entity.setStopLossPrice(result.stopLossPrice() == null ? null
                : BigDecimal.valueOf(result.stopLossPrice()));
        entity.setTakeProfit1(result.takeProfit1() == null ? null
                : BigDecimal.valueOf(result.takeProfit1()));
        entity.setTakeProfit2(result.takeProfit2() == null ? null
                : BigDecimal.valueOf(result.takeProfit2()));
        entity.setRiskRewardRatio(result.riskRewardRatio() == null ? null
                : BigDecimal.valueOf(result.riskRewardRatio()));
        entity.setIncludeInFinalPlan(result.includeInFinalPlan());
        entity.setPayloadJson(buildPayload(result));

        stockEvaluationRepository.save(entity);
    }

    private String buildPayload(StockEvaluateResult r) {
        return "{" +
                "\"valuationMode\":\"" + r.valuationMode() + "\"," +
                "\"includeInFinalPlan\":" + r.includeInFinalPlan() + "," +
                "\"rejectReason\":" + (r.rejectReason() == null ? "null" : "\"" + r.rejectReason() + "\"") + "," +
                "\"rationale\":\"" + escapeJson(r.rationale()) + "\"" +
                "}";
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
