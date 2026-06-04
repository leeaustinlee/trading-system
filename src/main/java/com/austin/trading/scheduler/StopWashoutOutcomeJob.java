package com.austin.trading.scheduler;

import com.austin.trading.domain.enums.StopWashoutOutcomeLabel;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.StopWashoutOutcomeEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.StopWashoutOutcomeRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Component
public class StopWashoutOutcomeJob {
    private final StructuralExitDecisionLogRepository logRepository;
    private final StopWashoutOutcomeRepository outcomeRepository;
    private final MarketIndexDailyRepository dailyRepository;
    public StopWashoutOutcomeJob(StructuralExitDecisionLogRepository logRepository, StopWashoutOutcomeRepository outcomeRepository, MarketIndexDailyRepository dailyRepository) { this.logRepository=logRepository; this.outcomeRepository=outcomeRepository; this.dailyRepository=dailyRepository; }

    @Scheduled(cron = "0 20 18 * * MON-FRI")
    @Transactional
    public RefreshSummary scheduledRefresh() { return refreshOutcomes(); }

    @Transactional
    public RefreshSummary refreshOutcomes() {
        int inserted=0, skipped=0;
        Result source = refreshUniverse(StopWashoutOutcomeEntity.BASIS_SOURCE_EXIT,
                logRepository.findSourceExitWithoutOutcome(PageRequest.of(0, 200)));
        Result arbiter = refreshUniverse(StopWashoutOutcomeEntity.BASIS_ARBITER_EXIT_SHADOW,
                logRepository.findArbiterExitWithoutOutcome(PageRequest.of(0, 200)));
        inserted += source.inserted + arbiter.inserted;
        skipped += source.skipped + arbiter.skipped;
        return new RefreshSummary(inserted, skipped);
    }

    private Result refreshUniverse(String outcomeBasis, List<StructuralExitDecisionLogEntity> logs) {
        int inserted=0, skipped=0;
        for (StructuralExitDecisionLogEntity log : logs) {
            if (log.getId() == null || outcomeRepository.existsByStructuralExitLogIdAndOutcomeBasis(log.getId(), outcomeBasis)) { skipped++; continue; }
            StopWashoutOutcomeEntity out = evaluate(log, outcomeBasis);
            outcomeRepository.save(out); inserted++;
        }
        return new Result(inserted, skipped);
    }

    StopWashoutOutcomeEntity evaluate(StructuralExitDecisionLogEntity log) {
        return evaluate(log, StopWashoutOutcomeEntity.BASIS_SOURCE_EXIT);
    }

    StopWashoutOutcomeEntity evaluate(StructuralExitDecisionLogEntity log, String outcomeBasis) {
        StopWashoutOutcomeEntity out = new StopWashoutOutcomeEntity();
        out.setStructuralExitLogId(log.getId()); out.setSymbol(log.getSymbol()); out.setExitSignalAt(log.getEvaluatedAt()); out.setSignalTier(log.getArbiterTier()); out.setOutcomeBasis(outcomeBasis); out.setSourceDecisionStatus(log.getSourceDecisionStatus()); out.setSignalPrice(log.getCurrentPrice());
        BigDecimal base = log.getCurrentPrice();
        if (base == null || base.signum() <= 0 || log.getSymbol() == null || log.getEvaluationDate() == null) { out.setOutcomeLabel(StopWashoutOutcomeLabel.DATA_INCOMPLETE.name()); return out; }
        LocalDate from = log.getEvaluationDate().plusDays(1); LocalDate to = log.getEvaluationDate().plusDays(21);
        List<MarketIndexDailyEntity> bars = dailyRepository.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(log.getSymbol(), from, to);
        if (bars.isEmpty()) { out.setOutcomeLabel(StopWashoutOutcomeLabel.DATA_INCOMPLETE.name()); return out; }
        List<MarketIndexDailyEntity> first10 = bars.stream().limit(10).toList();
        BigDecimal h1=maxHigh(first10.stream().limit(1).toList()); BigDecimal h3=maxHigh(first10.stream().limit(3).toList()); BigDecimal h5=maxHigh(first10.stream().limit(5).toList()); BigDecimal h10=maxHigh(first10);
        out.setHigh1d(h1); out.setHigh3d(h3); out.setHigh5d(h5); out.setHigh10d(h10);
        out.setT1MaxReturnPct(ret(base,h1)); out.setT3MaxReturnPct(ret(base,h3)); out.setT5MaxReturnPct(ret(base,h5)); out.setT10MaxReturnPct(ret(base,h10));
        BigDecimal refHigh = log.getRecentHigh() != null ? log.getRecentHigh() : base;
        boolean newHigh = first10.stream().skip(2).anyMatch(b -> b.getHighPrice()!=null && b.getHighPrice().compareTo(refHigh) > 0);
        out.setNewHigh3To10d(newHigh);
        if (newHigh && out.getT10MaxReturnPct()!=null && out.getT10MaxReturnPct().compareTo(new BigDecimal("3.0")) >= 0) out.setOutcomeLabel(StopWashoutOutcomeLabel.WASHOUT_REVERSAL.name());
        else if (newHigh) out.setOutcomeLabel(StopWashoutOutcomeLabel.THEME_CONTINUATION.name());
        else if (out.getT10MaxReturnPct()!=null && out.getT10MaxReturnPct().compareTo(BigDecimal.ZERO) <= 0) out.setOutcomeLabel(StopWashoutOutcomeLabel.TRUE_BREAKDOWN.name());
        else out.setOutcomeLabel(StopWashoutOutcomeLabel.NORMAL_STOP.name());
        return out;
    }
    private BigDecimal maxHigh(List<MarketIndexDailyEntity> bars){return bars.stream().map(MarketIndexDailyEntity::getHighPrice).filter(v->v!=null).max(Comparator.naturalOrder()).orElse(null);}    
    private BigDecimal ret(BigDecimal base, BigDecimal high){ if(base==null||high==null||base.signum()==0) return null; return high.subtract(base).divide(base,6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4,RoundingMode.HALF_UP);}    
    private record Result(int inserted, int skipped) {}
    public record RefreshSummary(int inserted, int skipped) {}
}
