package com.austin.trading.service;

import com.austin.trading.engine.ExitArbiterInput;
import com.austin.trading.engine.PositionDecisionEngine;
import com.austin.trading.engine.StructureAwareExitArbiter;
import com.austin.trading.engine.StructureAwareExitDecision;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionReviewLogEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class StructuralExitReplayBackfillService {
    public static final String MODE_REPLAY = "REPLAY";

    private final PositionReviewLogRepository reviewRepository;
    private final MarketIndexDailyRepository dailyRepository;
    private final StructuralExitDecisionLogRepository logRepository;
    private final StructureAwareExitArbiter arbiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StructuralExitReplayBackfillService(PositionReviewLogRepository reviewRepository,
                                               MarketIndexDailyRepository dailyRepository,
                                               StructuralExitDecisionLogRepository logRepository,
                                               StructureAwareExitArbiter arbiter) {
        this.reviewRepository = reviewRepository;
        this.dailyRepository = dailyRepository;
        this.logRepository = logRepository;
        this.arbiter = arbiter;
    }

    @Transactional
    public BackfillSummary backfillLastDays(LocalDate today, int days) {
        LocalDate from = today.minusDays(days - 1L);
        List<PositionReviewLogEntity> reviews = reviewRepository.findByReviewDateBetweenOrderByReviewDateAscIdAsc(from, today);
        int inserted = 0, skipped = 0, gaps = 0;
        for (PositionReviewLogEntity review : reviews) {
            if (review.getId() != null && logRepository.existsBySourceReviewLogIdAndMode(review.getId(), MODE_REPLAY)) {
                skipped++;
                continue;
            }
            ReplayContext ctx = buildContext(review);
            StructureAwareExitDecision decision = arbiter.evaluate(ctx.input());
            StructuralExitDecisionLogEntity row = toRow(review, ctx, decision);
            if (!decision.dataGaps().isEmpty()) gaps++;
            logRepository.save(row);
            inserted++;
        }
        return new BackfillSummary(reviews.size(), inserted, skipped, gaps);
    }

    private ReplayContext buildContext(PositionReviewLogEntity review) {
        List<MarketIndexDailyEntity> latestDesc = dailyRepository.findLatestBySymbolBefore(review.getSymbol(), review.getReviewDate(), PageRequest.of(0, 30));
        List<MarketIndexDailyEntity> bars = new ArrayList<>(latestDesc);
        Collections.reverse(bars);
        BigDecimal ma5 = ma(bars, 5), ma10 = ma(bars, 10), ma20 = ma(bars, 20);
        BigDecimal prevLow = previousLow(bars);
        BigDecimal recentHigh = high(bars);
        BigDecimal atr = atr(bars);
        BigDecimal volumeRatio = volumeRatio(bars);
        BigDecimal return5 = ret(bars, 5);
        BigDecimal return10 = ret(bars, 10);
        String structureStatus = inferStructureStatus(review.getCurrentPrice(), ma5, ma10, ma20, prevLow);
        String volumeStatus = inferVolumeStatus(volumeRatio);
        String rsStatus = "REPLAY_UNKNOWN";
        String chipStatus = "REPLAY_UNKNOWN";
        String themeStage = inferThemeStage(review.getSymbol(), review.getPayloadJson());
        boolean mainstream = isMainstreamThemeStage(themeStage);
        PositionEntity position = new PositionEntity();
        position.setSymbol(review.getSymbol());
        position.setAvgCost(review.getEntryPrice());
        position.setStopLossPrice(review.getPrevStopLoss());
        position.setTrailingStopPrice(review.getSuggestedStop());
        PositionDecisionEngine.PositionStatus status = parseStatus(review.getDecisionStatus());
        PositionDecisionEngine.PositionDecisionResult source = new PositionDecisionEngine.PositionDecisionResult(status, review.getReason(), review.getSuggestedStop(), PositionDecisionEngine.TrailingAction.NONE);
        BigDecimal hardStop = isExplicitHardStop(review.getReason()) ? review.getPrevStopLoss() : null;
        BigDecimal trailingStop = isExplicitHardStop(review.getReason()) ? null : review.getSuggestedStop();
        ExitArbiterInput input = ExitArbiterInput.builder()
                .position(position)
                .tradeRefType("POSITION_REVIEW")
                .tradeRefId(review.getPositionId())
                .sourceDecision(source)
                .entryPrice(review.getEntryPrice())
                .currentPrice(review.getCurrentPrice())
                .hardStopPrice(hardStop)
                .trailingStopPrice(trailingStop)
                .dynamicStopPrice(review.getSuggestedStop())
                .ma5(ma5).ma10(ma10).ma20(ma20).previousLow(prevLow).recentHigh(recentHigh).atr(atr)
                .volumeRatio(volumeRatio).return5d(return5).return10d(return10)
                .healthScore(null).structureStatus(structureStatus).volumeStatus(volumeStatus).relativeStrengthStatus(rsStatus).chipStatus(chipStatus)
                .themeStage(themeStage).themeRank(null).themeScore(null).mainstreamTheme(mainstream)
                .drawdownPct(null).momentumExitSignal(review.getReason() != null && review.getReason().toUpperCase(Locale.ROOT).contains("MOMENTUM"))
                .build();
        return new ReplayContext(input, bars, structureStatus, volumeStatus, rsStatus, chipStatus, themeStage, mainstream);
    }

    private StructuralExitDecisionLogEntity toRow(PositionReviewLogEntity review, ReplayContext ctx, StructureAwareExitDecision decision) {
        ExitArbiterInput input = ctx.input();
        StructuralExitDecisionLogEntity row = new StructuralExitDecisionLogEntity();
        row.setTradeRefType("POSITION_REVIEW");
        row.setTradeRefId(review.getPositionId());
        row.setSourceReviewLogId(review.getId());
        row.setSymbol(review.getSymbol());
        row.setEvaluatedAt(LocalDateTime.of(review.getReviewDate(), review.getReviewTime() == null ? java.time.LocalTime.MIDNIGHT : review.getReviewTime()));
        row.setEvaluationDate(review.getReviewDate());
        row.setReviewDate(review.getReviewDate());
        row.setMode(MODE_REPLAY);
        row.setSourceDecisionStatus(review.getDecisionStatus());
        row.setSourceExitReason(review.getReason());
        row.setArbiterTier(decision.tier().name());
        row.setArbiterReason(decision.reason());
        row.setRiskBlock(decision.riskBlock());
        row.setManualConfirmRequired(true);
        row.setAutoSellEnabled(false);
        row.setThemeState(decision.themeState().name());
        row.setThemeStage(input.themeStage());
        row.setMainstreamTheme(input.mainstreamTheme());
        row.setStructureState(decision.structureState().name());
        row.setStructureStatus(input.structureStatus());
        row.setVolumeStatus(input.volumeStatus());
        row.setRelativeStrengthStatus(input.relativeStrengthStatus());
        row.setChipStatus(input.chipStatus());
        row.setPriceState(decision.priceState().name());
        row.setCurrentPrice(input.currentPrice()); row.setEntryPrice(input.entryPrice());
        row.setHardStopPrice(input.hardStopPrice()); row.setTrailingStopPrice(input.trailingStopPrice()); row.setDynamicStopPrice(input.dynamicStopPrice());
        row.setMa5(input.ma5()); row.setMa10(input.ma10()); row.setMa20(input.ma20()); row.setPreviousLow(input.previousLow()); row.setRecentHigh(input.recentHigh()); row.setAtr(input.atr());
        row.setPriceTriggerJson(json(Map.of("priceState", decision.priceState().name(), "sourceReviewLogId", review.getId())));
        row.setLayerVotesJson(json(decision.layerVotes()));
        row.setDataGapsJson(json(decision.dataGaps()));
        row.setReasonJson(json(Map.of("reason", decision.reason(), "signals", decision.signals(), "sourceReason", review.getReason())));
        return row;
    }

    private PositionDecisionEngine.PositionStatus parseStatus(String status) {
        try { return PositionDecisionEngine.PositionStatus.valueOf(status == null ? "HOLD" : status); }
        catch (Exception e) { return PositionDecisionEngine.PositionStatus.HOLD; }
    }
    private String inferThemeStage(String symbol, String payload) {
        if (symbol == null || symbol.isBlank()) return "UNKNOWN";
        if (symbol.startsWith("00")) return "ETF_REPLAY_UNKNOWN";
        if (payload != null && payload.toUpperCase(Locale.ROOT).contains("DECAY")) return "DECAY";
        return "REPLAY_ACTIVE";
    }
    private boolean isMainstreamThemeStage(String stage) {
        String s = stage == null ? "" : stage.toUpperCase(Locale.ROOT);
        if (s.contains("UNKNOWN") || s.contains("DECAY") || s.contains("DEAD") || s.contains("BROKEN")) return false;
        return s.contains("MAINSTREAM") || s.contains("EXPAND") || s.contains("EMERGING") || s.contains("MID_TREND") || s.contains("STABLE") || s.contains("LEADERSHIP");
    }
    private boolean isExplicitHardStop(String reason) { return reason != null && (reason.contains("觸發停損") || reason.toUpperCase(Locale.ROOT).contains("HARD")); }
    private String inferStructureStatus(BigDecimal p, BigDecimal ma5, BigDecimal ma10, BigDecimal ma20, BigDecimal prevLow) {
        if (p == null) return "DATA_GAP";
        if (prevLow != null && p.compareTo(prevLow) < 0) return "LOWER_LOW";
        if (ma20 != null && p.compareTo(ma20) < 0) return "MA20_BREAK";
        if (ma10 != null && p.compareTo(ma10) < 0) return "MA10_BREAK";
        if (ma5 != null && p.compareTo(ma5) < 0) return "MA5_BREAK";
        return "BULL_ALIGNED";
    }
    private String inferVolumeStatus(BigDecimal ratio) {
        if (ratio == null) return "REPLAY_UNKNOWN";
        if (ratio.compareTo(new BigDecimal("1.8")) >= 0) return "SPIKE";
        return "NORMAL";
    }
    private BigDecimal ma(List<MarketIndexDailyEntity> bars, int n) {
        if (bars.size() < n) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = bars.size() - n; i < bars.size(); i++) sum = sum.add(bars.get(i).getClosePrice());
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }
    private BigDecimal previousLow(List<MarketIndexDailyEntity> bars) {
        if (bars.size() < 2) return null;
        return bars.subList(Math.max(0, bars.size() - 11), bars.size() - 1).stream().map(MarketIndexDailyEntity::getLowPrice).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
    }
    private BigDecimal high(List<MarketIndexDailyEntity> bars) { return bars.stream().map(MarketIndexDailyEntity::getHighPrice).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null); }
    private BigDecimal atr(List<MarketIndexDailyEntity> bars) {
        if (bars.size() < 2) return null;
        BigDecimal sum = BigDecimal.ZERO; int n = 0;
        for (int i = Math.max(1, bars.size() - 14); i < bars.size(); i++) {
            MarketIndexDailyEntity b = bars.get(i); if (b.getHighPrice()==null || b.getLowPrice()==null) continue;
            sum = sum.add(b.getHighPrice().subtract(b.getLowPrice()).abs()); n++;
        }
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }
    private BigDecimal volumeRatio(List<MarketIndexDailyEntity> bars) {
        if (bars.size() < 6 || bars.get(bars.size()-1).getVolume()==null) return null;
        long sum = 0; int n = 0;
        for (int i = bars.size() - 6; i < bars.size() - 1; i++) if (bars.get(i).getVolume()!=null) { sum += bars.get(i).getVolume(); n++; }
        if (n == 0 || sum == 0) return null;
        return BigDecimal.valueOf(bars.get(bars.size()-1).getVolume()).divide(BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP), 4, RoundingMode.HALF_UP);
    }
    private BigDecimal ret(List<MarketIndexDailyEntity> bars, int n) {
        if (bars.size() <= n) return null;
        BigDecimal prev = bars.get(bars.size()-1-n).getClosePrice(), last = bars.get(bars.size()-1).getClosePrice();
        if (prev == null || last == null || prev.signum()==0) return null;
        return last.subtract(prev).divide(prev, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch(Exception e) { return "{}"; } }

    public record BackfillSummary(int scanned, int inserted, int skipped, int dataGapRows) {}
    private record ReplayContext(ExitArbiterInput input, List<MarketIndexDailyEntity> bars, String structureStatus, String volumeStatus, String rsStatus, String chipStatus, String themeStage, boolean mainstream) {}
}
