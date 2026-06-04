package com.austin.trading.service;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StructuralExitReplayAnalysisServiceTest {

    @Test
    void computesKpisFromFormalReplayLedgerAndFlagsTrueBreakdownRecall() {
        StructuralExitDecisionLogRepository logRepo = mock(StructuralExitDecisionLogRepository.class);
        MarketIndexDailyRepository dailyRepo = mock(MarketIndexDailyRepository.class);
        StructuralExitReplayAnalysisService service = new StructuralExitReplayAnalysisService(logRepo, dailyRepo);

        StructuralExitDecisionLogEntity falseExit = row(1L, 10L, "2303", "2026-05-08", "EXIT", "OBSERVE_1D", "HEALTHY_PULLBACK", "EFFECTIVE_STOP_TOUCH", "ACTIVE");
        StructuralExitDecisionLogEntity trueBreakdownMiss = row(2L, 11L, "4739", "2026-05-15", "EXIT", "OBSERVE_1D", "HEALTHY_PULLBACK", "EFFECTIVE_STOP_TOUCH", "ACTIVE");
        StructuralExitDecisionLogEntity hard = row(3L, 12L, "8112", "2026-04-28", "EXIT", "HARD_EXIT_ALERT", "HEALTHY_PULLBACK", "HARD_STOP_BREACH", "ACTIVE");
        StructuralExitDecisionLogEntity gap = row(4L, 13L, "00631L", "2026-05-01", "EXIT", "DATA_GAP", "DATA_GAP", "EFFECTIVE_STOP_TOUCH", "DATA_GAP");
        when(logRepo.findByModeAndEvaluationDateBetweenOrderByEvaluationDateAscIdAsc(eq("REPLAY"), any(), any()))
                .thenReturn(List.of(falseExit, trueBreakdownMiss, hard, gap));
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("2303"), any(), any())).thenReturn(List.of(
                bar("2303", "2026-05-09", "100.0"), bar("2303", "2026-05-10", "120.0")));
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("4739"), any(), any())).thenReturn(List.of(
                bar("4739", "2026-05-16", "100.0"), bar("4739", "2026-05-17", "95.0")));
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("8112"), any(), any())).thenReturn(List.of(
                bar("8112", "2026-04-29", "102.0"), bar("8112", "2026-04-30", "105.0")));
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("00631L"), any(), any())).thenReturn(List.of());

        StructuralExitReplayAnalysisService.ReplayKpiReport report = service.analyze(LocalDate.of(2026, 4, 3), LocalDate.of(2026, 6, 2));

        assertEquals(4, report.replayLedgerEvents());
        assertEquals(4, report.sourceExitEvents());
        assertEquals(1, report.arbiterExitShadowEvents());
        assertEquals(2, report.washoutCount());
        assertEquals(1, report.trueBreakdownCount());
        assertEquals(0, report.trueBreakdownCaught());
        assertEquals(0.0, report.trueBreakdownRecallPct(), 0.001);
        assertEquals(50.0, report.falseExitPreventionPct(), 0.001);
        assertEquals(100.0, report.hardStopPreservationPct(), 0.001);
        assertEquals(25.0, report.dataGapRatePct(), 0.001);
        assertFalse(report.acceptanceGate().passed());
        assertTrue(report.trueBreakdownSignatures().stream().anyMatch(s -> s.symbol().equals("4739") && s.ma5ReclaimFailure()));
    }

    private static StructuralExitDecisionLogEntity row(Long id, Long sourceId, String symbol, String date, String source, String tier, String structure, String price, String theme) {
        StructuralExitDecisionLogEntity r = new StructuralExitDecisionLogEntity();
        set(r, "id", id);
        r.setSourceReviewLogId(sourceId);
        r.setMode("REPLAY");
        r.setSymbol(symbol);
        r.setEvaluationDate(LocalDate.parse(date));
        r.setReviewDate(LocalDate.parse(date));
        r.setSourceDecisionStatus(source);
        r.setArbiterTier(tier);
        r.setStructureState(structure);
        r.setPriceState(price);
        r.setThemeState(theme);
        r.setCurrentPrice(new BigDecimal("100.0"));
        r.setRecentHigh(new BigDecimal("110.0"));
        return r;
    }
    private static MarketIndexDailyEntity bar(String symbol, String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new MarketIndexDailyEntity(symbol, LocalDate.parse(date), c, c, c, c, 1000L);
    }
    private static void set(Object entity, String field, Object value) {
        try { var f=entity.getClass().getDeclaredField(field); f.setAccessible(true); f.set(entity,value); }
        catch(Exception e){ throw new AssertionError(e); }
    }
}
