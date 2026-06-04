package com.austin.trading.service;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class StructuralExitReplayAnalysisService {
    private final StructuralExitDecisionLogRepository logRepository;
    private final MarketIndexDailyRepository dailyRepository;

    public StructuralExitReplayAnalysisService(StructuralExitDecisionLogRepository logRepository, MarketIndexDailyRepository dailyRepository) {
        this.logRepository = logRepository;
        this.dailyRepository = dailyRepository;
    }

    public ReplayKpiReport analyze(LocalDate from, LocalDate to) {
        List<StructuralExitDecisionLogEntity> rows = logRepository.findByModeAndEvaluationDateBetweenOrderByEvaluationDateAscIdAsc("REPLAY", from, to);
        List<OutcomeRow> outcomes = rows.stream().map(this::classify).toList();
        long sourceExit = outcomes.stream().filter(o -> isSourceExit(o.row())).count();
        long arbExit = outcomes.stream().filter(o -> isArbiterExitLike(o.row())).count();
        long washout = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.WASHOUT_REVERSAL).count();
        long trueBreakdown = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.TRUE_BREAKDOWN).count();
        long trueCaught = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.TRUE_BREAKDOWN && isArbiterExitLike(o.row())).count();
        long falsePrevented = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.WASHOUT_REVERSAL && !isArbiterExitLike(o.row())).count();
        long hard = outcomes.stream().filter(o -> "HARD_STOP_BREACH".equals(o.row().getPriceState())).count();
        long hardPreserved = outcomes.stream().filter(o -> "HARD_STOP_BREACH".equals(o.row().getPriceState()) && "HARD_EXIT_ALERT".equals(o.row().getArbiterTier())).count();
        long dataGap = outcomes.stream().filter(o -> isDataGap(o.row())).count();
        Map<String, Long> confusion = new LinkedHashMap<>();
        for (OutcomeRow o : outcomes) {
            String key = (isSourceExit(o.row()) ? "SOURCE_EXIT" : "SOURCE_NON_EXIT") + "|" + (isArbiterExitLike(o.row()) ? "ARBITER_EXIT_LIKE" : "ARBITER_NON_EXIT");
            confusion.merge(key, 1L, Long::sum);
        }
        List<CaseRow> falseCases = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.WASHOUT_REVERSAL).map(this::caseRow).sorted(Comparator.comparing(CaseRow::t10MaxReturnPct, Comparator.nullsLast(Comparator.reverseOrder()))).limit(20).toList();
        List<CaseRow> breakdownCases = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.TRUE_BREAKDOWN).map(this::caseRow).sorted(Comparator.comparing(CaseRow::t10MaxReturnPct, Comparator.nullsLast(Comparator.naturalOrder()))).limit(20).toList();
        List<TrueBreakdownSignature> signatures = outcomes.stream().filter(o -> isSourceExit(o.row()) && o.label()==OutcomeLabel.TRUE_BREAKDOWN).map(this::signature).toList();
        Map<String, Long> dataSources = new LinkedHashMap<>();
        for (OutcomeRow o : outcomes) if (isDataGap(o.row())) dataGapSource(o.row()).forEach(s -> dataSources.merge(s, 1L, Long::sum));
        AcceptanceGate gate = new AcceptanceGate(
                pct(dataGap, rows.size()) < 20.0,
                hard == 0 || pct(hardPreserved, hard) == 100.0,
                pct(falsePrevented, washout) > 80.0,
                pct(trueCaught, trueBreakdown) > 60.0,
                pct(rows.stream().filter(r -> r.getSourceReviewLogId()!=null).count(), rows.size()) > 95.0
        );
        return new ReplayKpiReport(rows.size(), sourceExit, arbExit, washout, pct(washout, sourceExit), trueBreakdown, trueCaught, pct(trueCaught, trueBreakdown), pct(falsePrevented, washout), hard, hardPreserved, pct(hardPreserved, hard), dataGap, pct(dataGap, rows.size()), pct(rows.stream().filter(r -> r.getSourceReviewLogId()!=null).count(), rows.size()), confusion, falseCases, breakdownCases, signatures, dataSources, gate);
    }

    private OutcomeRow classify(StructuralExitDecisionLogEntity row) {
        List<MarketIndexDailyEntity> future = dailyRepository.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(row.getSymbol(), row.getEvaluationDate().plusDays(1), row.getEvaluationDate().plusDays(20));
        if (future.isEmpty() || row.getCurrentPrice()==null) return new OutcomeRow(row, OutcomeLabel.DATA_INCOMPLETE, null);
        BigDecimal maxHigh = future.stream().map(MarketIndexDailyEntity::getHighPrice).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
        if (maxHigh == null) return new OutcomeRow(row, OutcomeLabel.DATA_INCOMPLETE, null);
        BigDecimal r = maxHigh.subtract(row.getCurrentPrice()).divide(row.getCurrentPrice(), 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        if (r.compareTo(new BigDecimal("3.0")) >= 0) return new OutcomeRow(row, OutcomeLabel.WASHOUT_REVERSAL, r);
        if (r.compareTo(BigDecimal.ZERO) <= 0) return new OutcomeRow(row, OutcomeLabel.TRUE_BREAKDOWN, r);
        return new OutcomeRow(row, OutcomeLabel.NORMAL_STOP, r);
    }
    private boolean isSourceExit(StructuralExitDecisionLogEntity r) { return Set.of("EXIT","STOP","REDUCE").contains(String.valueOf(r.getSourceDecisionStatus())); }
    private boolean isArbiterExitLike(StructuralExitDecisionLogEntity r) { return Set.of("EXIT_REVIEW","HARD_EXIT_ALERT","REDUCE_REVIEW").contains(String.valueOf(r.getArbiterTier())); }
    private boolean isDataGap(StructuralExitDecisionLogEntity r) { return "DATA_GAP".equals(r.getArbiterTier()) || "DATA_GAP".equals(r.getThemeState()) || "DATA_GAP".equals(r.getStructureState()) || "DATA_GAP".equals(r.getPriceState()); }
    private List<String> dataGapSource(StructuralExitDecisionLogEntity r) {
        List<String> out = new ArrayList<>();
        if (String.valueOf(r.getSymbol()).startsWith("00")) out.add("ETF_THEME_MAPPING");
        if ("DATA_GAP".equals(r.getThemeState())) out.add("THEME_MAPPING_OR_LIFECYCLE");
        if ("DATA_GAP".equals(r.getStructureState())) out.add("TECHNICAL_HISTORY_OR_HEALTH_LOG");
        if ("DATA_GAP".equals(r.getPriceState())) out.add("PRICE_CONTEXT");
        if (r.getSourceReviewLogId()==null) out.add("REPLAY_JOIN");
        return out.isEmpty()?List.of("UNKNOWN"):out;
    }
    private CaseRow caseRow(OutcomeRow o) { var r=o.row(); return new CaseRow(r.getSymbol(), r.getEvaluationDate(), r.getSourceDecisionStatus(), r.getArbiterTier(), r.getThemeState(), r.getStructureState(), r.getPriceState(), r.getCurrentPrice(), o.t10MaxReturnPct(), r.getSourceExitReason()); }
    private TrueBreakdownSignature signature(OutcomeRow o) {
        var r=o.row();
        boolean repeated = true; // formal ledger can group later; conservative marker for source EXIT breakdown dataset.
        boolean lowerHigh = r.getRecentHigh()!=null && r.getCurrentPrice()!=null && r.getCurrentPrice().compareTo(r.getRecentHigh()) < 0;
        boolean ma5Fail = r.getMa5()==null || (r.getCurrentPrice()!=null && r.getCurrentPrice().compareTo(r.getMa5()) < 0) || !"HOLD_THESIS".equals(r.getArbiterTier());
        boolean themeDecay = String.valueOf(r.getThemeState()).contains("BROKEN") || String.valueOf(r.getThemeStage()).contains("DECAY");
        return new TrueBreakdownSignature(r.getSymbol(), r.getEvaluationDate(), r.getThemeState(), r.getStructureState(), r.getPriceState(), r.getRelativeStrengthStatus(), r.getVolumeStatus(), repeated, lowerHigh, ma5Fail, themeDecay);
    }
    private double pct(long n, long d) { return d == 0 ? 0.0 : BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(d), 4, RoundingMode.HALF_UP).doubleValue(); }

    enum OutcomeLabel { WASHOUT_REVERSAL, TRUE_BREAKDOWN, NORMAL_STOP, DATA_INCOMPLETE }
    private record OutcomeRow(StructuralExitDecisionLogEntity row, OutcomeLabel label, BigDecimal t10MaxReturnPct) {}
    public record CaseRow(String symbol, LocalDate date, String sourceDecision, String arbiterTier, String themeState, String structureState, String priceState, BigDecimal currentPrice, BigDecimal t10MaxReturnPct, String sourceReason) {}
    public record TrueBreakdownSignature(String symbol, LocalDate date, String themeState, String structureState, String priceState, String relativeStrengthStatus, String volumeStatus, boolean repeatedExitSignal, boolean lowerHigh, boolean ma5ReclaimFailure, boolean themeDecay) {}
    public record AcceptanceGate(boolean dataGap, boolean hardStopPreservation, boolean falseExitPrevention, boolean trueBreakdownRecall, boolean replayCoverage) { public boolean passed(){return dataGap&&hardStopPreservation&&falseExitPrevention&&trueBreakdownRecall&&replayCoverage;} }
    public record ReplayKpiReport(long replayLedgerEvents, long sourceExitEvents, long arbiterExitShadowEvents, long washoutCount, double washoutRatePct, long trueBreakdownCount, long trueBreakdownCaught, double trueBreakdownRecallPct, double falseExitPreventionPct, long hardStopEvents, long hardStopPreserved, double hardStopPreservationPct, long dataGapCount, double dataGapRatePct, double replayLedgerCoveragePct, Map<String,Long> confusionMatrix, List<CaseRow> topFalseExitCases, List<CaseRow> topTrueBreakdownCases, List<TrueBreakdownSignature> trueBreakdownSignatures, Map<String,Long> dataGapSources, AcceptanceGate acceptanceGate) {}
}
