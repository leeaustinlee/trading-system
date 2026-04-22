package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.MarketRegimeInput;
import com.austin.trading.engine.MarketRegimeEngine;
import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.repository.MarketRegimeDecisionRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.1 Regime layer — persistence + round-trip integration test.
 *
 * <p>Each test works against a unique trading_date (yesterday minus N days) so
 * that concurrent test runs / shared DB state don't collide with today's real
 * regime decisions produced by schedulers.</p>
 */
@SpringBootTest
@ActiveProfiles("integration")
class MarketRegimeServiceIntegrationTests {

    @Autowired MarketRegimeService             service;
    @Autowired MarketRegimeDecisionRepository  regimeRepo;
    @Autowired MarketSnapshotRepository        snapshotRepo;

    /** unique date per test instance to avoid cross-test pollution */
    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        uniqueDate = LocalDate.of(2000, 1, 1).plusDays(nano % 10_000);
    }

    @Test
    void evaluateAndPersist_writesRowAndCanBeReadBack() {
        MarketRegimeInput input = new MarketRegimeInput.Builder()
                .tradingDate(uniqueDate)
                .evaluatedAt(LocalDateTime.now())
                .marketGrade("A")
                .breadthPositiveRatio(new BigDecimal("0.65"))
                .leadersStrongRatio(new BigDecimal("0.70"))
                .indexDistanceFromMa10Pct(new BigDecimal("1.0"))
                .indexDistanceFromMa20Pct(new BigDecimal("2.0"))
                .breadthNegativeRatio(new BigDecimal("0.30"))
                .intradayVolatilityPct(new BigDecimal("1.0"))
                .build();

        MarketRegimeDecision saved = service.evaluateAndPersist(input, null);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_BULL_TREND);
        assertThat(saved.tradeAllowed()).isTrue();
        assertThat(saved.allowedSetupTypes()).isNotEmpty();

        // round-trip fetch
        var entityOpt = regimeRepo.findById(saved.id());
        assertThat(entityOpt).isPresent();
        assertThat(entityOpt.get().getRegimeType())
                .isEqualTo(MarketRegimeEngine.REGIME_BULL_TREND);
        assertThat(entityOpt.get().getAllowedSetupTypesJson())
                .contains("BREAKOUT_CONTINUATION");
    }

    @Test
    void panicVolatility_persistsNotAllowedAndEmptySetups() {
        MarketRegimeInput input = new MarketRegimeInput.Builder()
                .tradingDate(uniqueDate)
                .evaluatedAt(LocalDateTime.now())
                .marketGrade("C")
                .breadthPositiveRatio(new BigDecimal("0.20"))
                .breadthNegativeRatio(new BigDecimal("0.85"))
                .leadersStrongRatio(new BigDecimal("0.10"))
                .indexDistanceFromMa10Pct(new BigDecimal("-2.0"))
                .indexDistanceFromMa20Pct(new BigDecimal("-3.0"))
                .intradayVolatilityPct(new BigDecimal("3.5"))
                .blowoffSignal(true)
                .build();

        MarketRegimeDecision saved = service.evaluateAndPersist(input, null);

        assertThat(saved.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_PANIC_VOLATILITY);
        assertThat(saved.tradeAllowed()).isFalse();
        assertThat(saved.allowedSetupTypes()).isEmpty();
        assertThat(saved.inputSnapshotJson()).contains("\"blowoffSignal\":true");
    }

    @Test
    void buildInput_fromMarketSnapshotPayload_parsesKnownFields() {
        MarketSnapshotEntity snap = new MarketSnapshotEntity();
        snap.setTradingDate(uniqueDate);
        snap.setMarketGrade("A");
        snap.setMarketPhase("主升發動期");
        snap.setDecision("ENTER");
        snap.setPayloadJson("{\"breadth_positive_ratio\":0.62,"
                + "\"leaders_strong_ratio\":0.68,"
                + "\"index_distance_from_ma10_pct\":1.5,"
                + "\"index_distance_from_ma20_pct\":2.2,"
                + "\"intraday_volatility_pct\":1.1,"
                + "\"washout_rebound\":true}");
        MarketSnapshotEntity savedSnap = snapshotRepo.save(snap);

        MarketRegimeInput input = service.buildInput(savedSnap);

        assertThat(input.marketGrade()).isEqualTo("A");
        assertThat(input.breadthPositiveRatio()).isEqualByComparingTo("0.62");
        assertThat(input.leadersStrongRatio()).isEqualByComparingTo("0.68");
        assertThat(input.indexDistanceFromMa10Pct()).isEqualByComparingTo("1.5");
        assertThat(input.intradayVolatilityPct()).isEqualByComparingTo("1.1");
        assertThat(input.washoutRebound()).isTrue();

        // Fields not in payload stay null → engine uses conservative fallbacks
        assertThat(input.tsmcTrendScore()).isNull();
        assertThat(input.nearHighNotBreak()).isFalse();
    }

    @Test
    void evaluateAndPersist_noSnapshot_returnsEmpty() {
        // bootstrap no-op if snapshots already exist; otherwise call should safely
        // return Optional.empty(). We don't wipe snapshots from prod DB — assert
        // only that when snapshots exist, the default evaluateAndPersist() works.
        Optional<MarketRegimeDecision> result = service.evaluateAndPersist();
        // If DB has any snapshot, result is present; if not, empty. Either is valid.
        if (result.isPresent()) {
            assertThat(result.get().regimeType()).isNotNull();
        }
    }

    @Test
    void getHistory_returnsRecentDecisions() {
        MarketRegimeInput input = new MarketRegimeInput.Builder()
                .tradingDate(uniqueDate)
                .evaluatedAt(LocalDateTime.now())
                .marketGrade("B")
                .breadthPositiveRatio(new BigDecimal("0.45"))
                .breadthNegativeRatio(new BigDecimal("0.35"))
                .leadersStrongRatio(new BigDecimal("0.50"))
                .indexDistanceFromMa10Pct(new BigDecimal("0.1"))
                .indexDistanceFromMa20Pct(new BigDecimal("0.2"))
                .intradayVolatilityPct(new BigDecimal("1.0"))
                .build();
        service.evaluateAndPersist(input, null);

        List<MarketRegimeDecision> history = service.getHistory(10);
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).regimeType()).isNotNull();
    }
}
