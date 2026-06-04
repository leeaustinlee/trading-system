package com.austin.trading.service;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.StopOutcomeLedgerEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.StopOutcomeLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P0 Stop Outcome Ledger.
 *
 * <p>把 paper_trade 的 STOP / TRAILING / REVIEW 出場轉成「停損後 T+1/T+3/T+5/T+10」
 * 結果紀錄。這是 learning ledger，不會改 BUY/SELL/EXIT；用途是找出洗盤後大漲、真破線、
 * trailing stop 太敏感等型態，供 Adaptive Exit / Replay Backtest 後續使用。</p>
 */
@Service
public class StopOutcomeLedgerService {
    private static final List<Integer> OFFSETS = List.of(1, 3, 5, 10);

    private final PaperTradeRepository paperTradeRepository;
    private final MarketIndexDailyRepository marketIndexDailyRepository;
    private final StopOutcomeLedgerRepository stopOutcomeLedgerRepository;
    private final ObjectMapper objectMapper;

    public StopOutcomeLedgerService(PaperTradeRepository paperTradeRepository,
                                    MarketIndexDailyRepository marketIndexDailyRepository,
                                    StopOutcomeLedgerRepository stopOutcomeLedgerRepository,
                                    ObjectMapper objectMapper) {
        this.paperTradeRepository = paperTradeRepository;
        this.marketIndexDailyRepository = marketIndexDailyRepository;
        this.stopOutcomeLedgerRepository = stopOutcomeLedgerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RefreshSummary refresh(LocalDate fromExitDate, LocalDate referenceDate) {
        LocalDate from = fromExitDate != null ? fromExitDate : LocalDate.now().minusDays(90);
        LocalDate ref = referenceDate != null ? referenceDate : LocalDate.now();
        List<PaperTradeEntity> closed = paperTradeRepository
                .findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("CLOSED", from);
        int scanned = 0;
        int eligible = 0;
        int written = 0;
        int pendingData = 0;
        for (PaperTradeEntity trade : closed) {
            scanned++;
            if (!isStopLearningExit(trade)) continue;
            eligible++;
            StopOutcomeLedgerEntity row = upsertFromTrade(trade, ref);
            if ("PENDING_DATA".equals(row.getOutcomeLabel())) pendingData++;
            written++;
        }
        return new RefreshSummary(scanned, eligible, written, pendingData, from, ref);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(int days) {
        LocalDate from = LocalDate.now().minusDays(days > 0 ? days : 90);
        List<StopOutcomeLedgerEntity> rows = stopOutcomeLedgerRepository
                .findByExitDateGreaterThanEqualOrderByExitDateDescIdDesc(from);
        Map<String, Long> byOutcome = count(rows.stream().map(StopOutcomeLedgerEntity::getOutcomeLabel).toList());
        Map<String, Long> byReason = count(rows.stream().map(StopOutcomeLedgerEntity::getExitReason).toList());
        List<Map<String, Object>> samples = rows.stream().limit(80).map(this::toMap).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("mode", "READ_ONLY_LEARNING_LEDGER");
        out.put("productionDecisionAllowed", false);
        out.put("autoSellEnabled", false);
        out.put("days", days > 0 ? days : 90);
        out.put("fromExitDate", from);
        out.put("rowCount", rows.size());
        out.put("byOutcomeLabel", byOutcome);
        out.put("byExitReason", byReason);
        out.put("samples", samples);
        out.put("safetyNote", "Stop Outcome Ledger 只做停損後結果學習；不會自動放寬停損或覆蓋正式出場。");
        return out;
    }

    private StopOutcomeLedgerEntity upsertFromTrade(PaperTradeEntity trade, LocalDate referenceDate) {
        StopOutcomeLedgerEntity row = stopOutcomeLedgerRepository.findByPaperTradeId(trade.getId())
                .orElseGet(StopOutcomeLedgerEntity::new);
        row.setPaperTradeId(trade.getId());
        row.setSymbol(trade.getSymbol());
        row.setStockName(trade.getStockName());
        row.setEntryDate(trade.getEntryDate());
        row.setEntryPrice(trade.getEntryPrice());
        row.setExitDate(trade.getExitDate());
        row.setExitReason(trade.getExitReason());
        row.setExitPrice(exitPrice(trade));
        row.setThemeTag(trade.getThemeTag());
        row.setStrategyType(trade.getStrategyType());

        Map<Integer, BigDecimal> returns = new LinkedHashMap<>();
        for (int offset : OFFSETS) {
            BigDecimal pct = computePostExitReturn(trade, offset, referenceDate);
            returns.put(offset, pct);
            switch (offset) {
                case 1 -> row.setReturn1dAfterExit(pct);
                case 3 -> row.setReturn3dAfterExit(pct);
                case 5 -> row.setReturn5dAfterExit(pct);
                case 10 -> row.setReturn10dAfterExit(pct);
                default -> { }
            }
        }
        row.setMaxReturnAfterExit(max(returns.values().stream().toList()));
        row.setMinReturnAfterExit(min(returns.values().stream().toList()));
        row.setOutcomeLabel(classify(row));
        row.setEvidenceJson(evidenceJson(trade, row, returns));
        return stopOutcomeLedgerRepository.save(row);
    }

    private boolean isStopLearningExit(PaperTradeEntity trade) {
        if (trade == null || trade.getId() == null || trade.getExitDate() == null || exitPrice(trade) == null) return false;
        String reason = normalize(trade.getExitReason());
        return reason.contains("STOP") || reason.contains("TRAILING") || reason.contains("REVIEW_EXIT");
    }

    private BigDecimal computePostExitReturn(PaperTradeEntity trade, int offset, LocalDate referenceDate) {
        Optional<LocalDate> targetDate = tradingDayAfter(trade.getExitDate(), offset);
        if (targetDate.isEmpty()) return null;
        if (referenceDate != null && targetDate.get().isAfter(referenceDate)) return null;
        return marketIndexDailyRepository.findBySymbolAndTradingDate(trade.getSymbol(), targetDate.get())
                .map(MarketIndexDailyEntity::getClosePrice)
                .map(close -> pct(close, exitPrice(trade)))
                .orElse(null);
    }

    private Optional<LocalDate> tradingDayAfter(LocalDate asOf, int n) {
        if (asOf == null || n <= 0) return Optional.empty();
        List<LocalDate> dates = marketIndexDailyRepository.findTradingDatesAfter("t00", asOf, PageRequest.of(n - 1, 1));
        return dates.isEmpty() ? Optional.empty() : Optional.of(dates.get(0));
    }

    private String classify(StopOutcomeLedgerEntity row) {
        BigDecimal max = row.getMaxReturnAfterExit();
        BigDecimal min = row.getMinReturnAfterExit();
        BigDecimal r5 = row.getReturn5dAfterExit();
        BigDecimal r10 = row.getReturn10dAfterExit();
        if (max == null && min == null) return "PENDING_DATA";
        if (gte(max, "6.0") || gte(r10, "6.0") || gte(r5, "4.0")) return "WASHOUT_REVERSAL";
        if (gte(max, "3.0") && lte(min, "-2.0")) return "VOLATILE_WASHOUT_RISK";
        if (lte(max, "1.0") && (lte(min, "-3.0") || lte(r5, "-3.0") || lte(r10, "-5.0"))) return "TRUE_BREAKDOWN";
        return "MIXED_CHOP";
    }

    private String evidenceJson(PaperTradeEntity trade, StopOutcomeLedgerEntity row, Map<Integer, BigDecimal> returns) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("paperTradeId", trade.getId());
            n.put("symbol", trade.getSymbol());
            n.put("exitReason", trade.getExitReason());
            n.put("exitDate", String.valueOf(trade.getExitDate()));
            n.put("exitPrice", String.valueOf(exitPrice(trade)));
            n.put("outcomeLabel", row.getOutcomeLabel());
            ObjectNode r = n.putObject("returnsAfterExitPct");
            returns.forEach((offset, pct) -> {
                if (pct == null) r.putNull("tPlus" + offset);
                else r.put("tPlus" + offset, pct);
            });
            n.put("learningPurpose", "stop_outcome_only_no_production_exit_change");
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Long> count(List<String> values) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String v : values) out.merge(v == null ? "UNKNOWN" : v, 1L, Long::sum);
        return out;
    }

    private Map<String, Object> toMap(StopOutcomeLedgerEntity row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("paperTradeId", row.getPaperTradeId());
        out.put("symbol", row.getSymbol());
        out.put("stockName", row.getStockName());
        out.put("exitDate", row.getExitDate());
        out.put("exitReason", row.getExitReason());
        out.put("exitPrice", row.getExitPrice());
        out.put("themeTag", row.getThemeTag());
        out.put("strategyType", row.getStrategyType());
        out.put("return1dAfterExit", row.getReturn1dAfterExit());
        out.put("return3dAfterExit", row.getReturn3dAfterExit());
        out.put("return5dAfterExit", row.getReturn5dAfterExit());
        out.put("return10dAfterExit", row.getReturn10dAfterExit());
        out.put("maxReturnAfterExit", row.getMaxReturnAfterExit());
        out.put("minReturnAfterExit", row.getMinReturnAfterExit());
        out.put("outcomeLabel", row.getOutcomeLabel());
        return out;
    }

    private BigDecimal exitPrice(PaperTradeEntity trade) {
        if (trade == null) return null;
        return trade.getSimulatedExitPrice() != null ? trade.getSimulatedExitPrice() : trade.getExitPrice();
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0) return null;
        return price.subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal max(List<BigDecimal> values) {
        return values.stream().filter(v -> v != null).max(BigDecimal::compareTo).orElse(null);
    }

    private BigDecimal min(List<BigDecimal> values) {
        return values.stream().filter(v -> v != null).min(BigDecimal::compareTo).orElse(null);
    }

    private boolean gte(BigDecimal v, String threshold) {
        return v != null && v.compareTo(new BigDecimal(threshold)) >= 0;
    }

    private boolean lte(BigDecimal v, String threshold) {
        return v != null && v.compareTo(new BigDecimal(threshold)) <= 0;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    public record RefreshSummary(int scanned, int eligible, int written, int pendingData,
                                 LocalDate fromExitDate, LocalDate referenceDate) { }
}
