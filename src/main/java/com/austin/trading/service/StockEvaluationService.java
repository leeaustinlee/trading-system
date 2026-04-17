package com.austin.trading.service;

import com.austin.trading.dto.request.StockEvaluateRequest;
import com.austin.trading.dto.response.StockEvaluateResult;
import com.austin.trading.engine.StockEvaluationEngine;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.repository.StockEvaluationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockEvaluationService {

    private final StockEvaluationEngine stockEvaluationEngine;
    private final StockEvaluationRepository stockEvaluationRepository;

    public StockEvaluationService(
            StockEvaluationEngine stockEvaluationEngine,
            StockEvaluationRepository stockEvaluationRepository
    ) {
        this.stockEvaluationEngine = stockEvaluationEngine;
        this.stockEvaluationRepository = stockEvaluationRepository;
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
}
