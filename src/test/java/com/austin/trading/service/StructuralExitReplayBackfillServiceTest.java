package com.austin.trading.service;

import com.austin.trading.engine.*;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PositionReviewLogEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StructuralExitReplayBackfillServiceTest {

    @Test
    void backfillWritesReplayLedgerWithExactSourceReviewLogIdAndNoProductionAuthority() {
        PositionReviewLogRepository reviewRepo = mock(PositionReviewLogRepository.class);
        MarketIndexDailyRepository dailyRepo = mock(MarketIndexDailyRepository.class);
        StructuralExitDecisionLogRepository logRepo = mock(StructuralExitDecisionLogRepository.class);
        StructureAwareExitArbiter arbiter = new StructureAwareExitArbiter(new ThemeExitLayer(), new StructureExitLayer(), new PriceExitLayer());
        StructuralExitReplayBackfillService service = new StructuralExitReplayBackfillService(reviewRepo, dailyRepo, logRepo, arbiter);

        PositionReviewLogEntity review = review(101L, "2303", LocalDate.of(2026, 5, 8), "EXIT", "跌破移動停利 (stop=92.59)", "91.60", "70.00", "75.00", "92.59");
        when(reviewRepo.findByReviewDateBetweenOrderByReviewDateAscIdAsc(any(), any())).thenReturn(List.of(review));
        when(logRepo.existsBySourceReviewLogIdAndMode(101L, "REPLAY")).thenReturn(false);
        when(dailyRepo.findLatestBySymbolBefore(eq("2303"), eq(LocalDate.of(2026, 5, 8)), any(Pageable.class)))
                .thenReturn(List.of(
                        bar("2303", "2026-05-08", "91.60", 1000),
                        bar("2303", "2026-05-07", "92.00", 900),
                        bar("2303", "2026-05-06", "93.00", 800),
                        bar("2303", "2026-05-05", "94.00", 700),
                        bar("2303", "2026-05-04", "95.00", 600),
                        bar("2303", "2026-05-03", "96.00", 500),
                        bar("2303", "2026-05-02", "97.00", 400),
                        bar("2303", "2026-05-01", "98.00", 300),
                        bar("2303", "2026-04-30", "99.00", 200),
                        bar("2303", "2026-04-29", "100.00", 100)
                ));

        StructuralExitReplayBackfillService.BackfillSummary summary = service.backfillLastDays(LocalDate.of(2026, 6, 2), 60);

        assertEquals(1, summary.scanned());
        assertEquals(1, summary.inserted());
        ArgumentCaptor<StructuralExitDecisionLogEntity> captor = ArgumentCaptor.forClass(StructuralExitDecisionLogEntity.class);
        verify(logRepo).save(captor.capture());
        StructuralExitDecisionLogEntity row = captor.getValue();
        assertEquals(101L, row.getSourceReviewLogId());
        assertEquals("REPLAY", row.getMode());
        assertEquals("2303", row.getSymbol());
        assertEquals(LocalDate.of(2026, 5, 8), row.getReviewDate());
        assertEquals("EXIT", row.getSourceDecisionStatus());
        assertNotNull(row.getArbiterTier());
        assertNotNull(row.getThemeState());
        assertNotNull(row.getStructureState());
        assertNotNull(row.getPriceState());
        assertNotNull(row.getLayerVotesJson());
        assertFalse(row.getAutoSellEnabled());
        assertTrue(row.getManualConfirmRequired());
    }

    private static PositionReviewLogEntity review(Long id, String symbol, LocalDate date, String status, String reason, String current, String entry, String prevStop, String suggestedStop) {
        PositionReviewLogEntity r = new PositionReviewLogEntity();
        TestIds.setId(r, id);
        r.setPositionId(1L);
        r.setSymbol(symbol);
        r.setReviewDate(date);
        r.setReviewTime(LocalTime.of(9, 30));
        r.setReviewType("OPEN_POSITION");
        r.setDecisionStatus(status);
        r.setReason(reason);
        r.setCurrentPrice(new BigDecimal(current));
        r.setEntryPrice(new BigDecimal(entry));
        r.setPrevStopLoss(new BigDecimal(prevStop));
        r.setSuggestedStop(new BigDecimal(suggestedStop));
        return r;
    }

    private static MarketIndexDailyEntity bar(String symbol, String date, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        return new MarketIndexDailyEntity(symbol, LocalDate.parse(date), c, c, c, c, volume);
    }

    static class TestIds {
        static void setId(Object entity, Long id) {
            try {
                var f = entity.getClass().getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
