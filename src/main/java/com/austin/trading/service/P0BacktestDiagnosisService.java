package com.austin.trading.service;

import com.austin.trading.engine.PricePlanSanityEngine;
import com.austin.trading.engine.PricePlanSanityResult;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class P0BacktestDiagnosisService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PaperTradeRepository paperTradeRepository;
    private final CandidateForwardTrackingRepository forwardRepository;
    private final CandidateStockRepository candidateRepository;
    private final MarketIndexDailyRepository marketIndexDailyRepository;
    private final PricePlanSanityEngine pricePlanSanityEngine;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public P0BacktestDiagnosisService(PaperTradeRepository paperTradeRepository,
                                      CandidateForwardTrackingRepository forwardRepository,
                                      CandidateStockRepository candidateRepository,
                                      MarketIndexDailyRepository marketIndexDailyRepository,
                                      PricePlanSanityEngine pricePlanSanityEngine,
                                      ObjectMapper objectMapper) {
        this.paperTradeRepository = paperTradeRepository;
        this.forwardRepository = forwardRepository;
        this.candidateRepository = candidateRepository;
        this.marketIndexDailyRepository = marketIndexDailyRepository;
        this.pricePlanSanityEngine = pricePlanSanityEngine;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pricePlanSanity(int days) {
        Window window = window(days);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(window.start(), window.end());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> byViolation = new LinkedHashMap<>();
        for (PaperTradeEntity t : trades) {
            PricePlanSanityResult result = pricePlanSanityEngine.evaluate(new PricePlanSanityEngine.Input(
                    t.getEntryPrice(), t.getStopLossPrice(), t.getTarget1Price(), t.getTarget2Price(), t.getStrategyType(), false));
            List<String> violations = new ArrayList<>(result.rejectedReasons());
            if ("TP1_HIT".equalsIgnoreCase(t.getExitReason()) && t.getPnlPct() != null && t.getPnlPct().compareTo(ZERO) <= 0) {
                violations.add("TP1_HIT_NON_POSITIVE_PNL");
            }
            if (t.getEntryRrRatio() != null && t.getEntryRrRatio().compareTo(ZERO) <= 0) {
                violations.add("ENTRY_RR_RATIO_NON_POSITIVE");
            }
            violations = violations.stream().distinct().toList();
            if (!violations.isEmpty()) {
                for (String v : violations) byViolation.merge(v, 1, Integer::sum);
                rows.add(Map.ofEntries(
                        Map.entry("tradeId", value(t.getTradeId())),
                        Map.entry("entryDate", t.getEntryDate()),
                        Map.entry("symbol", value(t.getSymbol())),
                        Map.entry("entryPrice", value(t.getEntryPrice())),
                        Map.entry("stopLossPrice", value(t.getStopLossPrice())),
                        Map.entry("target1Price", value(t.getTarget1Price())),
                        Map.entry("target2Price", value(t.getTarget2Price())),
                        Map.entry("entryRrRatio", value(t.getEntryRrRatio())),
                        Map.entry("exitReason", value(t.getExitReason())),
                        Map.entry("pnlPct", value(t.getPnlPct())),
                        Map.entry("computedRrRatio", value(result.rrRatio())),
                        Map.entry("violations", violations)));
            }
        }
        Map<String, Object> out = base(window, "SHADOW_ONLY_READ_ONLY");
        out.put("totalTrades", trades.size());
        out.put("flaggedTrades", rows.size());
        out.put("byViolation", byViolation);
        out.put("rows", rows);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> themePropagation(int days) {
        Window window = window(days);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(window.start(), window.end());
        List<CandidateForwardTrackingEntity> forwards = forwardRepository.findByTradingDateBetween(window.start(), window.end());
        List<CandidateStockEntity> candidates = candidateRepository == null ? List.of()
                : candidateRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(window.start(), window.end());
        Map<String, CandidateForwardTrackingEntity> forwardByKey = new LinkedHashMap<>();
        for (CandidateForwardTrackingEntity f : forwards) forwardByKey.putIfAbsent(key(f.getTradingDate(), f.getStockId()), f);
        Map<String, CandidateStockEntity> candidateByKey = new LinkedHashMap<>();
        for (CandidateStockEntity c : candidates) candidateByKey.putIfAbsent(key(c.getTradingDate(), c.getSymbol()), c);

        long tradeTraceLoss = 0, candidateMappingGap = 0, trueOther = 0, alreadyMapped = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (PaperTradeEntity t : trades) {
            String paper = MainstreamThemeNormalizer.normalize(t.getThemeTag(), t.getEntryPayloadJson());
            CandidateForwardTrackingEntity f = forwardByKey.get(key(t.getEntryDate(), t.getSymbol()));
            CandidateStockEntity c = candidateByKey.get(key(t.getEntryDate(), t.getSymbol()));
            String forward = f == null ? "UNKNOWN" : MainstreamThemeNormalizer.normalize(f.getThemeTag(), f.getThemeReason());
            String candidate = c == null ? "UNKNOWN" : MainstreamThemeNormalizer.normalize(c.getThemeTag(), c.getReason());
            String bucket;
            if (isMainstream(paper)) {
                alreadyMapped++;
                bucket = "ALREADY_MAPPED";
            } else if (isMainstream(forward)) {
                tradeTraceLoss++;
                bucket = "TRADE_TRACE_LOSS";
            } else if (isMainstream(candidate)) {
                candidateMappingGap++;
                bucket = "CANDIDATE_MAPPING_GAP";
            } else {
                trueOther++;
                bucket = "TRUE_OTHER_UNMAPPED";
            }
            if (samples.size() < 50 && !"ALREADY_MAPPED".equals(bucket)) {
                samples.add(Map.of("date", t.getEntryDate(), "symbol", value(t.getSymbol()), "bucket", bucket,
                        "paperTheme", paper, "forwardTheme", forward, "candidateTheme", candidate));
            }
        }
        Map<String, Object> out = base(window, "SHADOW_ONLY_READ_ONLY");
        out.put("totalTrades", trades.size());
        out.put("alreadyMapped", alreadyMapped);
        out.put("tradeTraceLoss", tradeTraceLoss);
        out.put("candidateMappingGap", candidateMappingGap);
        out.put("trueOtherUnmapped", trueOther);
        out.put("samples", samples);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exitRuleComparison(int days) {
        Window window = window(days);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(window.start(), window.end());
        List<String> dataGaps = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, List<BigDecimal>> returnsByRule = new LinkedHashMap<>();
        for (String r : List.of("CURRENT", "MA5", "MA10", "PREVIOUS_LOW", "ATR_STRUCTURE", "TRAILING_PLUS_MA")) {
            returnsByRule.put(r, new ArrayList<>());
        }
        for (PaperTradeEntity t : trades) {
            LocalDate end = t.getExitDate() != null ? t.getExitDate().plusDays(1) : t.getEntryDate().plusDays(30);
            List<MarketIndexDailyEntity> bars = marketIndexDailyRepository.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
                    t.getSymbol(), t.getEntryDate(), end);
            if (bars.isEmpty()) {
                dataGaps.add("DATA_GAP: no daily bars for " + t.getSymbol() + " entryDate=" + t.getEntryDate());
                rows.add(Map.of("symbol", value(t.getSymbol()), "entryDate", t.getEntryDate(), "status", "DATA_GAP",
                        "dataGaps", List.of("DATA_GAP: no daily bars")));
                continue;
            }
            Map<String, Object> row = compareTrade(t, bars, dataGaps);
            rows.add(row);
            @SuppressWarnings("unchecked")
            Map<String, Object> ruleReturns = (Map<String, Object>) row.get("returnsPct");
            for (var e : ruleReturns.entrySet()) {
                if (e.getValue() instanceof BigDecimal bd) returnsByRule.get(e.getKey()).add(bd);
            }
        }
        Map<String, Object> out = base(window, dataGaps.size() == trades.size() && !trades.isEmpty() ? "DATA_GAP" : "OK");
        out.put("totalTrades", trades.size());
        out.put("rows", rows);
        out.put("summary", summarizeRules(returnsByRule));
        out.put("dataGaps", dataGaps.stream().distinct().toList());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exitRuleCaseTable(int days) {
        Window window = window(days);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(window.start(), window.end());
        List<String> dataGaps = new ArrayList<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        Map<String, Integer> byDiagnosis = new LinkedHashMap<>();
        for (PaperTradeEntity t : trades) {
            LocalDate end = t.getExitDate() != null ? t.getExitDate().plusDays(1) : t.getEntryDate().plusDays(30);
            List<MarketIndexDailyEntity> bars = marketIndexDailyRepository.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
                    t.getSymbol(), t.getEntryDate(), end);
            if (bars.isEmpty()) {
                String gap = "DATA_GAP: no daily bars for " + t.getSymbol() + " entryDate=" + t.getEntryDate();
                dataGaps.add(gap);
                Map<String, Object> row = Map.of("symbol", value(t.getSymbol()), "entryDate", t.getEntryDate(), "status", "DATA_GAP",
                        "diagnosis", "DATA_GAP_DAILY_BARS", "dataGaps", List.of(gap));
                cases.add(row);
                byDiagnosis.merge("DATA_GAP_DAILY_BARS", 1, Integer::sum);
                continue;
            }
            Map<String, Object> compared = compareTrade(t, bars, dataGaps);
            @SuppressWarnings("unchecked")
            Map<String, Object> returns = (Map<String, Object>) compared.get("returnsPct");
            @SuppressWarnings("unchecked")
            Map<String, Object> exitDates = (Map<String, Object>) compared.get("exitDates");
            BigDecimal current = returns.get("CURRENT") instanceof BigDecimal bd ? bd : null;
            String bestRule = null;
            BigDecimal bestReturn = null;
            for (var e : returns.entrySet()) {
                if ("CURRENT".equals(e.getKey()) || !(e.getValue() instanceof BigDecimal bd)) continue;
                if (bestReturn == null || bd.compareTo(bestReturn) > 0) {
                    bestReturn = bd;
                    bestRule = e.getKey();
                }
            }
            BigDecimal delta = current != null && bestReturn != null ? bestReturn.subtract(current).setScale(4, RoundingMode.HALF_UP) : null;
            String diagnosis = classifyExitCase(t, current, bestReturn, delta, bestRule);
            byDiagnosis.merge(diagnosis, 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", value(t.getSymbol()));
            row.put("entryDate", t.getEntryDate());
            row.put("exitDate", t.getExitDate());
            row.put("entryPrice", value(t.getEntryPrice()));
            row.put("exitReason", value(t.getExitReason()));
            row.put("strategyType", value(t.getStrategyType()));
            row.put("currentReturnPct", value(current));
            row.put("bestAlternativeRule", value(bestRule));
            row.put("bestAlternativeReturnPct", value(bestReturn));
            row.put("currentVsBestDeltaPct", value(delta));
            row.put("maxDrawdownPct", value(maxDrawdownPct(bars, t.getEntryPrice())));
            row.put("diagnosis", diagnosis);
            row.put("readableConclusion", readableExitConclusion(diagnosis, bestRule, delta));
            row.put("returnsPct", returns);
            row.put("exitDates", exitDates);
            row.put("status", "OK");
            cases.add(row);
        }
        Map<String, Object> out = base(window, dataGaps.size() == trades.size() && !trades.isEmpty() ? "DATA_GAP" : "OK");
        out.put("totalTrades", trades.size());
        out.put("byDiagnosis", byDiagnosis);
        out.put("cases", cases);
        out.put("dataGaps", dataGaps.stream().distinct().toList());
        out.put("safetyNote", "READ_ONLY_DIAGNOSTIC_ONLY: exit-case table compares rules only; it does not change live exit behavior");
        return out;
    }

    private Map<String, Object> compareTrade(PaperTradeEntity t, List<MarketIndexDailyEntity> bars, List<String> globalGaps) {
        Map<String, Object> returns = new LinkedHashMap<>();
        Map<String, Object> exitDates = new LinkedHashMap<>();
        returns.put("CURRENT", t.getPnlPct());
        exitDates.put("CURRENT", t.getExitDate());
        RuleExit ma5 = exitByMa(t, bars, 5);
        RuleExit ma10 = exitByMa(t, bars, 10);
        RuleExit prevLow = exitByPreviousLow(t, bars, 10);
        RuleExit atr = exitByAtr(t, bars, 14);
        RuleExit hybrid = firstExit(ma5, ma10);
        putRule("MA5", ma5, returns, exitDates, globalGaps, t);
        putRule("MA10", ma10, returns, exitDates, globalGaps, t);
        putRule("PREVIOUS_LOW", prevLow, returns, exitDates, globalGaps, t);
        putRule("ATR_STRUCTURE", atr, returns, exitDates, globalGaps, t);
        putRule("TRAILING_PLUS_MA", hybrid, returns, exitDates, globalGaps, t);
        return Map.of("symbol", value(t.getSymbol()), "entryDate", t.getEntryDate(), "entryPrice", value(t.getEntryPrice()),
                "status", "OK", "returnsPct", returns, "exitDates", exitDates);
    }

    private void putRule(String name, RuleExit exit, Map<String, Object> returns, Map<String, Object> dates,
                         List<String> gaps, PaperTradeEntity t) {
        if (exit.dataGap() != null) {
            returns.put(name, "DATA_GAP");
            dates.put(name, null);
            gaps.add(exit.dataGap() + " symbol=" + t.getSymbol() + " entryDate=" + t.getEntryDate());
        } else {
            returns.put(name, exit.returnPct());
            dates.put(name, exit.exitDate());
        }
    }

    private RuleExit exitByMa(PaperTradeEntity t, List<MarketIndexDailyEntity> bars, int n) {
        if (bars.size() < n) return RuleExit.gap("DATA_GAP: MA" + n + " requires bars");
        for (int i = 0; i < bars.size(); i++) {
            BigDecimal ma = ma(bars, i, n);
            BigDecimal close = bars.get(i).getClosePrice();
            if (ma != null && close != null && close.compareTo(ma) < 0) return RuleExit.of(bars.get(i).getTradingDate(), pct(close, t.getEntryPrice()));
        }
        MarketIndexDailyEntity last = bars.get(bars.size() - 1);
        return RuleExit.of(last.getTradingDate(), pct(last.getClosePrice(), t.getEntryPrice()));
    }

    private RuleExit exitByPreviousLow(PaperTradeEntity t, List<MarketIndexDailyEntity> bars, int lookback) {
        if (bars.size() < 2) return RuleExit.gap("DATA_GAP: previous-low requires bars");
        for (int i = 1; i < bars.size(); i++) {
            BigDecimal low = previousLow(bars, i, lookback);
            BigDecimal close = bars.get(i).getClosePrice();
            if (low != null && close != null && close.compareTo(low) < 0) return RuleExit.of(bars.get(i).getTradingDate(), pct(close, t.getEntryPrice()));
        }
        MarketIndexDailyEntity last = bars.get(bars.size() - 1);
        return RuleExit.of(last.getTradingDate(), pct(last.getClosePrice(), t.getEntryPrice()));
    }

    private RuleExit exitByAtr(PaperTradeEntity t, List<MarketIndexDailyEntity> bars, int n) {
        if (bars.size() < n + 1) return RuleExit.gap("DATA_GAP: ATR" + n + " requires bars");
        for (int i = n; i < bars.size(); i++) {
            BigDecimal atr = atr(bars, i, n);
            BigDecimal close = bars.get(i).getClosePrice();
            if (atr != null && close != null && t.getEntryPrice() != null) {
                BigDecimal stop = t.getEntryPrice().subtract(atr.multiply(new BigDecimal("2")));
                BigDecimal ma10 = ma(bars, i, 10);
                boolean structureBroken = ma10 != null && close.compareTo(ma10) < 0;
                if (close.compareTo(stop) < 0 && structureBroken) return RuleExit.of(bars.get(i).getTradingDate(), pct(close, t.getEntryPrice()));
            }
        }
        MarketIndexDailyEntity last = bars.get(bars.size() - 1);
        return RuleExit.of(last.getTradingDate(), pct(last.getClosePrice(), t.getEntryPrice()));
    }

    private RuleExit firstExit(RuleExit a, RuleExit b) {
        if (a.dataGap() != null) return b;
        if (b.dataGap() != null) return a;
        return !a.exitDate().isAfter(b.exitDate()) ? a : b;
    }

    private Map<String, Object> summarizeRules(Map<String, List<BigDecimal>> returnsByRule) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : returnsByRule.entrySet()) {
            List<BigDecimal> vals = e.getValue();
            BigDecimal avg = vals.isEmpty() ? null : vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(vals.size()), 4, RoundingMode.HALF_UP);
            long wins = vals.stream().filter(v -> v.compareTo(ZERO) > 0).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sampleSize", vals.size());
            item.put("avgReturnPct", avg);
            item.put("winRatePct", vals.isEmpty() ? null : pct(BigDecimal.valueOf(wins), BigDecimal.valueOf(vals.size())));
            out.put(e.getKey(), item);
        }
        return out;
    }

    private BigDecimal ma(List<MarketIndexDailyEntity> bars, int i, int n) {
        if (i + 1 < n) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = i + 1 - n; j <= i; j++) {
            if (bars.get(j).getClosePrice() == null) return null;
            sum = sum.add(bars.get(j).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal previousLow(List<MarketIndexDailyEntity> bars, int i, int lookback) {
        BigDecimal min = null;
        for (int j = Math.max(0, i - lookback); j < i; j++) {
            BigDecimal low = bars.get(j).getLowPrice();
            if (low != null && (min == null || low.compareTo(min) < 0)) min = low;
        }
        return min;
    }

    private BigDecimal atr(List<MarketIndexDailyEntity> bars, int i, int n) {
        if (i < n) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = i - n + 1; j <= i; j++) {
            if (j <= 0 || bars.get(j).getHighPrice() == null || bars.get(j).getLowPrice() == null || bars.get(j - 1).getClosePrice() == null) return null;
            BigDecimal tr = bars.get(j).getHighPrice().subtract(bars.get(j).getLowPrice()).abs()
                    .max(bars.get(j).getHighPrice().subtract(bars.get(j - 1).getClosePrice()).abs())
                    .max(bars.get(j).getLowPrice().subtract(bars.get(j - 1).getClosePrice()).abs());
            sum = sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal exit, BigDecimal entry) {
        if (exit == null || entry == null || entry.signum() == 0) return null;
        return exit.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal maxDrawdownPct(List<MarketIndexDailyEntity> bars, BigDecimal entry) {
        if (bars == null || bars.isEmpty() || entry == null || entry.signum() == 0) return null;
        BigDecimal worst = null;
        for (MarketIndexDailyEntity bar : bars) {
            BigDecimal low = bar.getLowPrice() != null ? bar.getLowPrice() : bar.getClosePrice();
            BigDecimal dd = pct(low, entry);
            if (dd != null && (worst == null || dd.compareTo(worst) < 0)) worst = dd;
        }
        return worst;
    }

    private String classifyExitCase(PaperTradeEntity t, BigDecimal current, BigDecimal bestReturn, BigDecimal delta, String bestRule) {
        if (current == null || bestReturn == null || delta == null) return "DATA_GAP_RETURN";
        if (delta.compareTo(new BigDecimal("2.0")) >= 0) {
            String reason = t.getExitReason() == null ? "" : t.getExitReason().toUpperCase();
            if (reason.contains("STOP") || reason.contains("TRAIL")) return "STOP_TOO_SENSITIVE";
            return "EXIT_TOO_EARLY";
        }
        if (current.compareTo(bestReturn) >= 0 || delta.abs().compareTo(new BigDecimal("0.5")) <= 0) return "CURRENT_RULE_OK";
        return bestRule != null && bestRule.contains("MA") ? "STRUCTURE_CONFIRMATION_MAY_HELP" : "ALTERNATIVE_RULE_MAY_HELP";
    }

    private String readableExitConclusion(String diagnosis, String bestRule, BigDecimal delta) {
        String gain = delta == null ? "DATA_GAP" : delta.toPlainString() + "%";
        return switch (diagnosis) {
            case "STOP_TOO_SENSITIVE" -> "現行 stop/trailing stop 可能太敏感；若改用 " + bestRule + "，本案例理論改善 " + gain + "。";
            case "EXIT_TOO_EARLY" -> "本案例疑似出場太早；最佳替代規則 " + bestRule + " 理論改善 " + gain + "。";
            case "STRUCTURE_CONFIRMATION_MAY_HELP" -> "加入均線/結構確認可能略有幫助，但改善幅度有限：" + gain + "。";
            case "ALTERNATIVE_RULE_MAY_HELP" -> "替代出場規則可能略優，需更多樣本驗證：" + gain + "。";
            case "CURRENT_RULE_OK" -> "現行出場規則與替代規則差距不大，暫不構成過早出場證據。";
            default -> "資料不足，需補日線/出場/報酬資料後再判讀。";
        };
    }

    private boolean isMainstream(String v) {
        return v != null && !"UNKNOWN".equals(v) && !"OTHER".equals(v);
    }

    private String key(LocalDate date, String symbol) { return date + "|" + symbol; }
    private Window window(int days) { int d = days > 0 ? days : 60; LocalDate end = LocalDate.now(); return new Window(d, end.minusDays(d), end); }
    private Map<String, Object> base(Window w, String status) { Map<String, Object> out = new LinkedHashMap<>(); out.put("status", status); out.put("days", w.days()); out.put("startDate", w.start()); out.put("endDate", w.end()); out.put("mode", "SHADOW_ONLY_READ_ONLY"); return out; }
    private Object value(Object v) { return v == null ? "DATA_GAP" : v; }

    private record Window(int days, LocalDate start, LocalDate end) {}
    private record RuleExit(LocalDate exitDate, BigDecimal returnPct, String dataGap) {
        static RuleExit of(LocalDate date, BigDecimal ret) { return new RuleExit(date, ret, null); }
        static RuleExit gap(String gap) { return new RuleExit(null, null, gap); }
    }
}
