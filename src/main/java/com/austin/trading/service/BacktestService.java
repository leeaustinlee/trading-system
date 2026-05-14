package com.austin.trading.service;

import com.austin.trading.engine.BacktestMetricsEngine;
import com.austin.trading.engine.BacktestMetricsEngine.*;
import com.austin.trading.entity.BacktestRunEntity;
import com.austin.trading.entity.BacktestTradeEntity;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.BacktestRunRepository;
import com.austin.trading.repository.BacktestTradeRepository;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BacktestRunRepository runRepository;
    private final BacktestTradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final BacktestMetricsEngine metricsEngine;
    private final ScoreConfigService configService;
    private final ObjectMapper objectMapper;
    private final PaperTradeRepository paperTradeRepository;
    private final CandidateForwardTrackingRepository candidateForwardTrackingRepository;

    public BacktestService(BacktestRunRepository runRepository,
                            BacktestTradeRepository tradeRepository,
                            PositionRepository positionRepository,
                            BacktestMetricsEngine metricsEngine,
                            ScoreConfigService configService,
                            ObjectMapper objectMapper,
                            PaperTradeRepository paperTradeRepository,
                            CandidateForwardTrackingRepository candidateForwardTrackingRepository) {
        this.runRepository = runRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.metricsEngine = metricsEngine;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.paperTradeRepository = paperTradeRepository;
        this.candidateForwardTrackingRepository = candidateForwardTrackingRepository;
    }

    @Transactional
    public BacktestRunEntity runBacktest(LocalDate startDate, LocalDate endDate, String runName, String notes) {
        // 建立 run 紀錄
        BacktestRunEntity run = new BacktestRunEntity();
        run.setRunName(runName != null ? runName : "Backtest " + startDate + " ~ " + endDate);
        run.setRunType("RANGE_BACKTEST");
        run.setStartDate(startDate);
        run.setEndDate(endDate);
        run.setConfigVersion(configService.getString("scoring.version", "v2.0-bc-sniper"));
        run.setConfigSnapshotJson(captureConfigSnapshot());
        run.setNotes(notes);
        run.setStatus("RUNNING");
        run = runRepository.save(run);

        try {
            // 從 position history 重建交易列表
            LocalDateTime from = startDate.atStartOfDay();
            LocalDateTime to = endDate.plusDays(1).atStartOfDay();
            List<PositionEntity> closedPositions = positionRepository.findClosedBetween(from, to);

            List<BacktestTradeInput> tradeInputs = new ArrayList<>();
            for (PositionEntity pos : closedPositions) {
                BacktestTradeEntity trade = toBacktestTrade(run.getId(), pos);
                tradeRepository.save(trade);

                tradeInputs.add(new BacktestTradeInput(
                        trade.getPnlPct(), trade.getHoldingDays() != null ? trade.getHoldingDays() : 0,
                        trade.getMfePct(), trade.getMaePct(),
                        trade.getEntryTriggerType()));
            }

            // 計算績效指標
            BacktestMetricsResult metrics = metricsEngine.compute(tradeInputs);

            // 更新 run
            run.setTotalTrades(metrics.totalTrades());
            run.setWinCount(metrics.winCount());
            run.setLossCount(metrics.lossCount());
            run.setWinRate(metrics.winRate());
            run.setAvgReturnPct(metrics.avgReturnPct());
            run.setAvgHoldingDays(metrics.avgHoldingDays());
            run.setMaxDrawdownPct(metrics.maxDrawdownPct());
            run.setProfitFactor(metrics.profitFactor());
            run.setBestTradePct(metrics.bestTradePct());
            run.setWorstTradePct(metrics.worstTradePct());
            run.setTotalPnl(metrics.totalPnl());
            run.setStatus("SUCCESS");
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);

            log.info("[Backtest] 完成 runId={} trades={} winRate={}%",
                    run.getId(), metrics.totalTrades(), metrics.winRate());
            return run;

        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setNotes((run.getNotes() != null ? run.getNotes() + " | " : "") + "ERROR: " + e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);
            log.error("[Backtest] 失敗 runId={}: {}", run.getId(), e.getMessage());
            throw e;
        }
    }

    public List<BacktestRunEntity> getAllRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc();
    }

    public BacktestRunEntity getRun(Long id) {
        return runRepository.findById(id).orElseThrow(() -> new RuntimeException("Backtest run not found: " + id));
    }

    public List<BacktestTradeEntity> getTrades(Long runId) {
        return tradeRepository.findByBacktestRunIdOrderByEntryDateAsc(runId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recentDiagnosis(int days) {
        int window = days > 0 ? days : 30;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(start, end);
        List<CandidateForwardTrackingEntity> candidates =
                candidateForwardTrackingRepository.findByTradingDateBetween(start, end);

        List<BacktestTradeInput> closedInputs = trades.stream()
                .filter(t -> "CLOSED".equalsIgnoreCase(t.getStatus()))
                .map(t -> new BacktestTradeInput(
                        t.getPnlPct(),
                        t.getHoldingDays() != null ? t.getHoldingDays() : 0,
                        t.getMfePct(),
                        t.getMaePct(),
                        t.getStrategyType()))
                .toList();
        BacktestMetricsResult metrics = metricsEngine.compute(closedInputs);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("window", Map.of("start", start, "end", end, "days", window));
        Map<String, Object> tradeLayer = new java.util.LinkedHashMap<>();
        tradeLayer.put("totalTrades", metrics.totalTrades());
        tradeLayer.put("winRate", metrics.winRate());
        tradeLayer.put("avgPnl", metrics.avgReturnPct());
        tradeLayer.put("avgHoldingDays", metrics.avgHoldingDays());
        tradeLayer.put("maxDrawdown", metrics.maxDrawdownPct());
        tradeLayer.put("profitFactor", metrics.profitFactor());
        tradeLayer.put("expectancy", expectancy(trades));
        out.put("tradeLayer", tradeLayer);
        out.put("strategyLayer", groupPaperTrades(trades, PaperTradeEntity::getStrategyType));
        out.put("exitLayer", groupPaperTrades(trades, PaperTradeEntity::getExitReason));
        out.put("marketRegimeLayer", groupPaperTrades(trades, PaperTradeEntity::getEntryRegime));
        out.put("themeLayer", groupPaperTrades(trades, PaperTradeEntity::getThemeTag));
        out.put("aiLayer", Map.of(
                "candidateSamples", candidates.size(),
                "scoreReturnCorrelation", correlation(candidates),
                "dataGaps", aiDataGaps(candidates)
        ));
        out.put("rootCauseRanking", rootCauseRanking(trades, candidates));
        return out;
    }

    private List<Map<String, Object>> rootCauseRanking(List<PaperTradeEntity> trades,
                                                       List<CandidateForwardTrackingEntity> candidates) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(rootCause("invalidPricePlanPct", trades.size(),
                trades.stream().filter(t -> t.getSanityResult() != null && !"PASS".equalsIgnoreCase(t.getSanityResult())).toList(),
                "sanityResult != PASS",
                "Price plan sanity failed before/at entry; inspect entry/stop/target consistency."));

        BigDecimal minRr = configDecimal("price_plan.min_rr.setup", new BigDecimal("1.8"));
        items.add(rootCause("lowRrPct", trades.size(),
                trades.stream().filter(t -> t.getEntryRrRatio() == null || t.getEntryRrRatio().compareTo(minRr) < 0).toList(),
                "entryRrRatio < " + minRr + " or DATA_GAP:null",
                "Denominator is all paper_trade rows in the window; null RR is counted as DATA_GAP/low RR."));

        items.add(rootCause("earlyExitPct", trades.size(),
                trades.stream().filter(t -> isEarlyExit(t)).toList(),
                "STOP_LOSS/TRAILING_STOP with MFE > 0 or return5d > pnlPct",
                "Exit may be cutting trades before their forward path matures; compare exit rule with forward truth."));

        BigDecimal minStopPct = configDecimal("price_plan.stop_min_loss_pct", new BigDecimal("1.5"));
        items.add(rootCause("stopTooTightPct", trades.size(),
                trades.stream().filter(t -> isStopTooTight(t, minStopPct)).toList(),
                "entry-to-stop distance < " + minStopPct + "% or <= 2%",
                "Stop distance is tighter than configured minimum or operational 2% floor."));

        items.add(rootCause("themeMisalignmentPct", trades.size(),
                trades.stream().filter(t -> {
                    String theme = MainstreamThemeNormalizer.normalize(t.getThemeTag(), t.getEntryPayloadJson());
                    return "UNKNOWN".equals(theme) || "OTHER".equals(theme);
                }).toList(),
                "themeTag UNKNOWN/OTHER/其他強勢股 or unmapped",
                "Theme evidence is weak or unmapped, so the trade cannot be tied to a mainstream bucket."));

        items.add(rootCause("regimeMismatchPct", trades.size(),
                trades.stream().filter(t -> isWeakRegime(t.getEntryRegime())).toList(),
                "entryRegime C/WEAK/UNKNOWN/DATA_GAP",
                "Entry happened in weak or unknown market regime."));

        BigDecimal highScore = configDecimal("scoring.grade_b_min", new BigDecimal("6.5"));
        List<CandidateForwardTrackingEntity> candidatesWithT5 = candidates.stream()
                .filter(c -> c.getT5CloseReturnPct() != null)
                .toList();
        items.add(rootCause("aiScoreFailurePct", candidatesWithT5.size(),
                candidatesWithT5.stream().filter(c -> c.getFinalScore() != null
                        && c.getFinalScore().compareTo(highScore) >= 0
                        && c.getT5CloseReturnPct().compareTo(BigDecimal.ZERO) <= 0).toList(),
                "finalScore >= " + highScore + " and return5d <= 0",
                "High-ranked candidates failed their forward T5 return; denominator is candidate_forward_tracking rows with non-null T5 return. Missing T5 is reported under aiLayer.dataGaps instead of counted as failure."));

        return items.stream()
                .sorted((a, b) -> ((BigDecimal) b.get("pct")).compareTo((BigDecimal) a.get("pct")))
                .toList();
    }

    private Map<String, Object> rootCause(String name,
                                          int total,
                                          List<?> matches,
                                          String reason,
                                          String interpretation) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        int count = matches.size();
        BigDecimal pct = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        item.put("name", name);
        item.put("count", count);
        item.put("total", total);
        item.put("pct", pct);
        item.put("evidenceSample", matches.stream().limit(5).map(o -> evidence(o, reason)).toList());
        item.put("interpretation", total == 0 ? "DATA_GAP: no rows available for this denominator. " + interpretation : interpretation);
        return item;
    }

    private Map<String, Object> evidence(Object row, String reason) {
        if (row instanceof PaperTradeEntity t) {
            Map<String, Object> sample = new java.util.LinkedHashMap<>();
            sample.put("symbol", t.getSymbol());
            sample.put("date", t.getEntryDate());
            sample.put("reason", reason);
            return sample;
        }
        CandidateForwardTrackingEntity c = (CandidateForwardTrackingEntity) row;
        Map<String, Object> sample = new java.util.LinkedHashMap<>();
        sample.put("symbol", c.getStockId());
        sample.put("date", c.getTradingDate());
        sample.put("reason", reason);
        return sample;
    }

    private boolean isEarlyExit(PaperTradeEntity t) {
        String exit = t.getExitReason() == null ? "" : t.getExitReason();
        boolean stopExit = "STOP_LOSS".equalsIgnoreCase(exit) || "TRAILING_STOP".equalsIgnoreCase(exit);
        if (!stopExit) return false;
        boolean hadMfe = t.getMfePct() != null && t.getMfePct().compareTo(BigDecimal.ZERO) > 0;
        boolean forwardBetter = t.getReturn5d() != null && t.getPnlPct() != null && t.getReturn5d().compareTo(t.getPnlPct()) > 0;
        return hadMfe || forwardBetter;
    }

    private boolean isStopTooTight(PaperTradeEntity t, BigDecimal minStopPct) {
        if (t.getEntryPrice() == null || t.getStopLossPrice() == null || t.getEntryPrice().signum() <= 0) return false;
        BigDecimal pct = t.getEntryPrice().subtract(t.getStopLossPrice()).abs()
                .divide(t.getEntryPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return pct.compareTo(minStopPct) < 0 || pct.compareTo(new BigDecimal("2.0")) <= 0;
    }

    private boolean isWeakRegime(String regime) {
        if (regime == null || regime.isBlank()) return true;
        String r = regime.toUpperCase();
        return "C".equals(r) || r.contains("WEAK") || r.contains("UNKNOWN") || r.contains("DATA_GAP");
    }

    private BigDecimal configDecimal(String key, BigDecimal defaultValue) {
        if (configService == null) return defaultValue;
        BigDecimal value = configService.getDecimal(key, defaultValue);
        return value != null ? value : defaultValue;
    }

    private BigDecimal expectancy(List<PaperTradeEntity> trades) {
        List<BigDecimal> pnl = trades.stream().map(PaperTradeEntity::getPnlPct).filter(v -> v != null).toList();
        if (pnl.isEmpty()) return null;
        BigDecimal sum = pnl.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(pnl.size()), 4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> groupPaperTrades(List<PaperTradeEntity> trades,
                                                 java.util.function.Function<PaperTradeEntity, String> classifier) {
        Map<String, List<PaperTradeEntity>> grouped = trades.stream()
                .collect(java.util.stream.Collectors.groupingBy(t -> {
                    String key = classifier.apply(t);
                    return key == null || key.isBlank() ? "UNKNOWN" : key;
                }, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        grouped.forEach((key, rows) -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("total", rows.size());
            item.put("avgPnl", expectancy(rows));
            out.put(key, item);
        });
        if (out.isEmpty()) out.put("DATA_GAP", "no paper trades in window");
        return out;
    }

    private Object correlation(List<CandidateForwardTrackingEntity> rows) {
        List<CandidateForwardTrackingEntity> usable = rows.stream()
                .filter(r -> r.getFinalScore() != null && r.getT5CloseReturnPct() != null)
                .toList();
        if (usable.size() < 3) return "DATA_GAP";
        double avgX = usable.stream().mapToDouble(r -> r.getFinalScore().doubleValue()).average().orElse(0);
        double avgY = usable.stream().mapToDouble(r -> r.getT5CloseReturnPct().doubleValue()).average().orElse(0);
        double num = 0, denX = 0, denY = 0;
        for (CandidateForwardTrackingEntity r : usable) {
            double dx = r.getFinalScore().doubleValue() - avgX;
            double dy = r.getT5CloseReturnPct().doubleValue() - avgY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        if (denX == 0 || denY == 0) return "DATA_GAP";
        return BigDecimal.valueOf(num / Math.sqrt(denX * denY)).setScale(4, RoundingMode.HALF_UP);
    }

    private List<String> aiDataGaps(List<CandidateForwardTrackingEntity> rows) {
        List<String> gaps = new ArrayList<>();
        if (rows.isEmpty()) gaps.add("DATA_GAP: candidate_forward_tracking empty for recent window");
        long missingReturn = rows.stream().filter(r -> r.getT5CloseReturnPct() == null).count();
        if (missingReturn > 0) gaps.add("DATA_GAP: missing T5 return rows=" + missingReturn);
        if (rows.stream().filter(r -> r.getFinalScore() != null && r.getT5CloseReturnPct() != null).count() < 3) {
            gaps.add("DATA_GAP: insufficient score/return pairs for Claude/Codex correlation");
        }
        return gaps;
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private BacktestTradeEntity toBacktestTrade(Long runId, PositionEntity pos) {
        BigDecimal pnlPct = BigDecimal.ZERO;
        if (pos.getClosePrice() != null && pos.getAvgCost() != null && pos.getAvgCost().signum() > 0) {
            pnlPct = pos.getClosePrice().subtract(pos.getAvgCost())
                    .divide(pos.getAvgCost(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }
        int holdingDays = pos.getOpenedAt() != null && pos.getClosedAt() != null
                ? (int) ChronoUnit.DAYS.between(pos.getOpenedAt().toLocalDate(), pos.getClosedAt().toLocalDate())
                : 0;

        // 從 exitReason/note 推斷 entry trigger type
        String triggerType = inferEntryTriggerType(pos);

        BacktestTradeEntity t = new BacktestTradeEntity();
        t.setBacktestRunId(runId);
        t.setPositionId(pos.getId());
        t.setSymbol(pos.getSymbol());
        t.setStockName(pos.getStockName());
        t.setEntryDate(pos.getOpenedAt() != null ? pos.getOpenedAt().toLocalDate() : LocalDate.now());
        t.setExitDate(pos.getClosedAt() != null ? pos.getClosedAt().toLocalDate() : null);
        t.setEntryPrice(pos.getAvgCost());
        t.setExitPrice(pos.getClosePrice());
        t.setPnlPct(pnlPct);
        t.setHoldingDays(holdingDays);
        t.setEntryTriggerType(triggerType);
        t.setEntryReason(pos.getNote());
        t.setExitReason(pos.getExitReason());
        return t;
    }

    private String inferEntryTriggerType(PositionEntity pos) {
        String note = pos.getNote() != null ? pos.getNote().toLowerCase() : "";
        if (note.contains("breakout") || note.contains("突破")) return "BREAKOUT";
        if (note.contains("pullback") || note.contains("回測")) return "PULLBACK";
        if (note.contains("reversal") || note.contains("轉強")) return "REVERSAL";
        if (note.contains("watchlist") || note.contains("ready")) return "WATCHLIST_READY";
        return "UNKNOWN";
    }

    private String captureConfigSnapshot() {
        try {
            Map<String, String> snapshot = new java.util.LinkedHashMap<>();
            configService.getAll().forEach(c -> snapshot.put(c.configKey(), c.configValue()));
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
