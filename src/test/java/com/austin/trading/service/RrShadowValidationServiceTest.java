package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.RrShadowValidationEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.RrShadowValidationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RrShadowValidationServiceTest {

    @Test
    void backfillPersistsShadowRowsAndIsIdempotentByPaperTradeId() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        Map<Long, RrShadowValidationEntity> rows = new LinkedHashMap<>();
        RrShadowValidationRepository validationRepo = validationRepository(rows);
        PaperTradeEntity trade = trade(1L, "2330", "100", "95", "102");
        trade.setReturn5d(new BigDecimal("-1.2"));

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of());

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        service.backfill(60);
        service.backfill(60);

        assertThat(rows).hasSize(1);
        RrShadowValidationService.Summary summary = service.summary(60);
        assertThat(summary.totalRows()).isEqualTo(1);
        assertThat(summary.wouldBlockCount()).isEqualTo(1);
        assertThat(summary.avoidedLoserCount()).isEqualTo(1);
        assertThat(summary.missedWinnerCount()).isZero();
    }

    @Test
    void summaryKeepsReturnDataGapsSeparateAndDetectsMissedWinner() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        RrShadowValidationRepository validationRepo = validationRepository(new LinkedHashMap<>());
        PaperTradeEntity trade = trade(2L, "2454", "100", "95", "102");
        CandidateForwardTrackingEntity forward = new CandidateForwardTrackingEntity();
        forward.setTradingDate(trade.getEntryDate());
        forward.setStockId("2454");
        forward.setT5CloseReturnPct(new BigDecimal("4.2"));

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(forward));

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        service.backfill(60);
        RrShadowValidationService.Summary summary = service.summary(60);

        assertThat(summary.wouldBlockCount()).isEqualTo(1);
        assertThat(summary.blockedAvgReturnT5()).isEqualByComparingTo("4.2000");
        assertThat(summary.missedWinnerCount()).isEqualTo(1);
        assertThat(summary.dataGaps()).containsEntry("T1", 1).containsEntry("T3", 1).containsEntry("T10", 1);
        assertThat(summary.blockedReturnCoveragePct()).isEqualByComparingTo("100.00");
    }

    @Test
    void backfillUsesMarketIndexDailyPartialHorizonsWithoutCandidateForwardRows() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        Map<Long, RrShadowValidationEntity> rows = new LinkedHashMap<>();
        RrShadowValidationRepository validationRepo = validationRepository(rows);
        PaperTradeEntity trade = trade(3L, "2330", "100", "95", "102");
        trade.setEntryDate(LocalDate.now().minusDays(20));

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of());
        when(marketRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
                any(), any(), any())).thenReturn(List.of(
                bar("2330", trade.getEntryDate().plusDays(1), "101"),
                bar("2330", trade.getEntryDate().plusDays(2), "102"),
                bar("2330", trade.getEntryDate().plusDays(3), "104")
        ));
        when(marketRepo.findTradingDatesAfter(any(), any(), any())).thenReturn(List.of());

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService(), marketRepo);

        service.backfill(60);
        RrShadowValidationService.Summary summary = service.summary(60);
        RrShadowValidationEntity row = rows.get(3L);

        assertThat(row.getT1ReturnPct()).isEqualByComparingTo("1.0000");
        assertThat(row.getT3ReturnPct()).isEqualByComparingTo("4.0000");
        assertThat(row.getT5ReturnPct()).isNull();
        assertThat(summary.blockedReturnCoveragePct()).isEqualByComparingTo("100.00");
        assertThat(summary.dataGaps()).containsEntry("T1", 0).containsEntry("T5", 1).containsEntry("T10", 1);
        assertThat(summary.coverageGaps().missingBenchmark()).contains("t00@" + trade.getEntryDate());
        assertThat(summary.coverageGaps().missingHorizons().get("2330@" + trade.getEntryDate()))
                .containsExactly("T5", "T10");
    }

    @Test
    void backfillExpandedCreatesForwardCandidateRowsWithProxyPlanAndShadowOnlyNote() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        Map<String, RrShadowValidationEntity> rows = new LinkedHashMap<>();
        RrShadowValidationRepository validationRepo = validationRepositoryBySource(rows);
        CandidateForwardTrackingEntity candidate = forwardCandidate(10L, "3037", "MOMENTUM_CHASE", "WATCH", "AI");
        candidate.setEntryPriceAtDecision(new BigDecimal("100"));
        candidate.setT5CloseReturnPct(new BigDecimal("4.5"));
        candidate.setThemeTag("AI_SERVER");

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of());
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(candidate));

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        Map<String, Object> response = service.backfillExpanded(180);

        RrShadowValidationEntity row = rows.get("F10");
        assertThat(response).containsEntry("paperRowsProcessed", 0)
                .containsEntry("forwardRowsProcessed", 1)
                .containsEntry("forwardRowsSkippedMissingPrice", 0)
                .containsEntry("upsertedRows", 1);
        assertThat(response.get("sourceTypes").toString()).contains("FORWARD_CANDIDATE");
        assertThat(row.getSourceType()).isEqualTo("FORWARD_CANDIDATE");
        assertThat(row.getPaperTradeId()).isNull();
        assertThat(row.getSourceForwardTrackingId()).isEqualTo(10L);
        assertThat(row.getStopLossPrice()).isEqualByComparingTo("94.0000");
        assertThat(row.getTarget1Price()).isEqualByComparingTo("108.0000");
        assertThat(row.getTarget2Price()).isEqualByComparingTo("112.0000");
        assertThat(row.getValidationNote()).contains("PROXY_RR_PLAN_FROM_FORWARD_CANDIDATE").contains("SHADOW_ONLY");
        assertThat(row.getFinalDecision()).isEqualTo("WATCH");
        assertThat(row.getThemeTag()).isEqualTo("AI_SERVER");
    }

    @Test
    void backfillExpandedSkipsForwardCandidateMissingEntryPrice() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        Map<String, RrShadowValidationEntity> rows = new LinkedHashMap<>();
        RrShadowValidationRepository validationRepo = validationRepositoryBySource(rows);
        CandidateForwardTrackingEntity candidate = forwardCandidate(11L, "3037", "SETUP", "WAIT", "AI");

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of());
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(candidate));

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        Map<String, Object> response = service.backfillExpanded(180);

        assertThat(rows).isEmpty();
        assertThat(response).containsEntry("forwardRowsProcessed", 0)
                .containsEntry("forwardRowsSkippedMissingPrice", 1)
                .containsEntry("upsertedRows", 0);
    }

    @Test
    void summaryIncludesExpandedGroupsAndPromotionReadiness() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        Map<String, RrShadowValidationEntity> rows = new LinkedHashMap<>();
        RrShadowValidationEntity paper = row("PAPER_TRADE", "2330", "SETUP", null, "AI_SERVER", "-1.0");
        RrShadowValidationEntity forward = row("FORWARD_CANDIDATE", "3037", "MOMENTUM_CHASE", "WATCH", "AI_SERVER", "4.2");
        rows.put("P1", paper);
        rows.put("F2", forward);
        RrShadowValidationRepository validationRepo = validationRepositoryBySource(rows);
        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        RrShadowValidationService.Summary summary = service.summary(60);

        assertThat(summary.bySourceType()).containsKeys("PAPER_TRADE", "FORWARD_CANDIDATE");
        assertThat(summary.byStrategyType()).containsKey("MOMENTUM_CHASE");
        assertThat(summary.byFinalDecision()).containsKey("WATCH");
        assertThat(summary.byThemeTag()).containsKey("AI_SERVER");
        assertThat(summary.byRootCauseBucket()).containsKey("STOP_TOO_WIDE");
        assertThat(summary.promotionReadiness().status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(summary.promotionReadiness().minSampleThreshold()).isEqualTo(50);
        assertThat(summary.sampleSizeWarning()).contains("INSUFFICIENT_SAMPLE");
    }

    private PaperTradeEntity trade(Long id, String symbol, String entry, String stop, String target1) {
        PaperTradeEntity trade = new PaperTradeEntity();
        ReflectionTestUtils.setField(trade, "id", id);
        trade.setEntryDate(LocalDate.now().minusDays(5));
        trade.setSymbol(symbol);
        trade.setStatus("CLOSED");
        trade.setStrategyType("SETUP");
        trade.setEntryPrice(new BigDecimal(entry));
        trade.setStopLossPrice(new BigDecimal(stop));
        trade.setTarget1Price(new BigDecimal(target1));
        return trade;
    }

    private MarketIndexDailyEntity bar(String symbol, LocalDate date, String close) {
        return new MarketIndexDailyEntity(symbol, date, new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), 1000L);
    }

    private CandidateForwardTrackingEntity forwardCandidate(Long id, String symbol, String strategy, String decision, String grade) {
        CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
        ReflectionTestUtils.setField(e, "id", id);
        e.setTradingDate(LocalDate.now().minusDays(5));
        e.setStockId(symbol);
        e.setPrimaryStrategy(strategy);
        e.setFinalDecision(decision);
        e.setFinalScore(new BigDecimal("7.2"));
        e.setGrade(grade);
        e.setGateName("RR_SHADOW");
        return e;
    }

    private RrShadowValidationEntity row(String sourceType,
                                         String symbol,
                                         String strategy,
                                         String decision,
                                         String theme,
                                         String t5) {
        RrShadowValidationEntity row = new RrShadowValidationEntity();
        row.setSourceType(sourceType);
        row.setTradingDate(LocalDate.now().minusDays(4));
        row.setSymbol(symbol);
        row.setStrategyType(strategy);
        row.setFinalDecision(decision);
        row.setThemeTag(theme);
        row.setShadowStatus(RiskRewardShadowGateService.FAIL);
        row.setRootCauseBucket("STOP_TOO_WIDE");
        row.setT5ReturnPct(new BigDecimal(t5));
        row.setAvoidedLoserFlag(new BigDecimal(t5).compareTo(BigDecimal.ZERO) < 0);
        row.setMissedWinnerFlag(new BigDecimal(t5).compareTo(new BigDecimal("3.0")) > 0);
        return row;
    }

    private RrShadowValidationRepository validationRepository(Map<Long, RrShadowValidationEntity> rows) {
        RrShadowValidationRepository repository = mock(RrShadowValidationRepository.class);
        when(repository.findByPaperTradeId(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(repository.findBySourceForwardTrackingId(any())).thenAnswer(inv -> Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            RrShadowValidationEntity entity = inv.getArgument(0);
            if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", (long) rows.size() + 1);
            rows.put(entity.getPaperTradeId(), entity);
            return entity;
        });
        when(repository.findByTradingDateBetweenOrderByTradingDateAscIdAsc(any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            LocalDate end = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> !r.getTradingDate().isBefore(start) && !r.getTradingDate().isAfter(end))
                    .toList();
        });
        when(repository.countByTradingDateBetween(any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            LocalDate end = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> !r.getTradingDate().isBefore(start) && !r.getTradingDate().isAfter(end))
                    .count();
        });
        return repository;
    }

    private RrShadowValidationRepository validationRepositoryBySource(Map<String, RrShadowValidationEntity> rows) {
        RrShadowValidationRepository repository = mock(RrShadowValidationRepository.class);
        when(repository.findByPaperTradeId(any())).thenAnswer(inv -> Optional.ofNullable(rows.get("P" + inv.getArgument(0))));
        when(repository.findBySourceForwardTrackingId(any())).thenAnswer(inv -> Optional.ofNullable(rows.get("F" + inv.getArgument(0))));
        when(repository.save(any())).thenAnswer(inv -> {
            RrShadowValidationEntity entity = inv.getArgument(0);
            if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", (long) rows.size() + 1);
            if ("FORWARD_CANDIDATE".equals(entity.getSourceType())) {
                rows.put("F" + entity.getSourceForwardTrackingId(), entity);
            } else {
                rows.put("P" + entity.getPaperTradeId(), entity);
            }
            return entity;
        });
        when(repository.findByTradingDateBetweenOrderByTradingDateAscIdAsc(any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            LocalDate end = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> !r.getTradingDate().isBefore(start) && !r.getTradingDate().isAfter(end))
                    .toList();
        });
        when(repository.countByTradingDateBetween(any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            LocalDate end = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> !r.getTradingDate().isBefore(start) && !r.getTradingDate().isAfter(end))
                    .count();
        });
        return repository;
    }
}
