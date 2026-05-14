package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RrRootCauseDiagnosisServiceTest {

    private PaperTradeRepository paperTradeRepository;
    private CandidateForwardTrackingRepository forwardTrackingRepository;
    private CandidateStockRepository candidateStockRepository;
    private RrRootCauseDiagnosisService service;

    @BeforeEach
    void setUp() {
        paperTradeRepository = mock(PaperTradeRepository.class);
        forwardTrackingRepository = mock(CandidateForwardTrackingRepository.class);
        candidateStockRepository = mock(CandidateStockRepository.class);
        service = new RrRootCauseDiagnosisService(
                paperTradeRepository,
                forwardTrackingRepository,
                candidateStockRepository,
                new RiskRewardShadowGateService(),
                new ObjectMapper()
        );
    }

    @Test
    void diagnosisClassifiesLowRrRootCauseBuckets() {
        when(paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any()))
                .thenReturn(List.of(
                        trade("2330", "100", "90", "115", null, "{\"dayHigh\":101,\"dayLow\":96}"),
                        trade("2317", "100", "97", "102", null, "{\"dayHigh\":103,\"dayLow\":98}"),
                        trade("2454", "100", null, "110", null, null)
                ));
        when(forwardTrackingRepository.findByTradingDateBetween(any(), any())).thenReturn(List.of());
        when(candidateStockRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any()))
                .thenReturn(List.of());

        var result = service.diagnose(60);

        assertThat(result.totalTrades()).isEqualTo(3);
        assertThat(result.lowRrTradeCount()).isEqualTo(3);
        assertThat(result.rootCauseBuckets()).anySatisfy(bucket -> {
            assertThat(bucket.name()).isEqualTo("STOP_TOO_WIDE");
            assertThat(bucket.count()).isEqualTo(1);
            assertThat(bucket.sampleSymbols()).contains("2330");
        });
        assertThat(result.rootCauseBuckets()).anySatisfy(bucket -> {
            assertThat(bucket.name()).isEqualTo("TARGET_TOO_CLOSE");
            assertThat(bucket.count()).isEqualTo(1);
            assertThat(bucket.sampleSymbols()).contains("2317");
        });
        assertThat(result.rootCauseBuckets()).anySatisfy(bucket -> {
            assertThat(bucket.name()).isEqualTo("DATA_GAP");
            assertThat(bucket.count()).isEqualTo(1);
            assertThat(bucket.sampleSymbols()).contains("2454");
        });
        assertThat(result.dataGaps().toString()).contains("DATA_GAP");
    }

    @Test
    void partialForwardReturnDoesNotFailImpactAndMarksInsufficientSample() {
        PaperTradeEntity loser = trade("3037", "100", "95", "108", null, null);
        loser.setReturn1d(new BigDecimal("-1.2"));
        PaperTradeEntity winner = trade("3661", "100", "95", "108", null, null);
        winner.setReturn5d(new BigDecimal("6.5"));
        CandidateForwardTrackingEntity forwardOnly = forward("3661", null, null, new BigDecimal("7.0"), null);

        when(paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any()))
                .thenReturn(List.of(loser, winner));
        when(forwardTrackingRepository.findByTradingDateBetween(any(), any())).thenReturn(List.of(forwardOnly));
        when(candidateStockRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any()))
                .thenReturn(List.of());

        var result = service.diagnose(60);

        assertThat(result.shadowImpact().wouldBlockCount()).isEqualTo(2);
        assertThat(result.shadowImpact().blockedAvgForwardReturnT1()).isEqualByComparingTo("-1.2000");
        assertThat(result.shadowImpact().blockedAvgForwardReturnT5()).isEqualByComparingTo("6.5000");
        assertThat(result.shadowImpact().missedWinnerCount()).isEqualTo(1);
        assertThat(result.shadowImpact().avoidedLoserCount()).isEqualTo(1);
        assertThat(result.shadowImpact().status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(result.shadowImpact().dataGaps().toString()).contains("DATA_GAP").contains("INSUFFICIENT_SAMPLE");
    }

    private PaperTradeEntity trade(String symbol,
                                   String entry,
                                   String stop,
                                   String target1,
                                   String target2,
                                   String payloadJson) {
        PaperTradeEntity trade = new PaperTradeEntity();
        trade.setEntryDate(LocalDate.now().minusDays(5));
        trade.setSymbol(symbol);
        trade.setStatus("CLOSED");
        trade.setStrategyType("SETUP");
        trade.setEntryPrice(bd(entry));
        trade.setStopLossPrice(bd(stop));
        trade.setTarget1Price(bd(target1));
        trade.setTarget2Price(bd(target2));
        trade.setPayloadJson(payloadJson);
        trade.setThemeTag("UNKNOWN");
        trade.setEntryRegime("BULL");
        return trade;
    }

    private CandidateForwardTrackingEntity forward(String symbol,
                                                   BigDecimal t1,
                                                   BigDecimal t3,
                                                   BigDecimal t5,
                                                   BigDecimal t10) {
        CandidateForwardTrackingEntity entity = new CandidateForwardTrackingEntity();
        entity.setTradingDate(LocalDate.now().minusDays(5));
        entity.setStockId(symbol);
        entity.setT1CloseReturnPct(t1);
        entity.setT3CloseReturnPct(t3);
        entity.setT5CloseReturnPct(t5);
        entity.setT10CloseReturnPct(t10);
        return entity;
    }

    private BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
