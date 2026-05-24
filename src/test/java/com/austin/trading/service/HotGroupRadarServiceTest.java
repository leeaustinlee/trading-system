package com.austin.trading.service;

import com.austin.trading.entity.CandidateThemeRadarTraceEntity;
import com.austin.trading.entity.HotGroupRadarSnapshotEntity;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import com.austin.trading.repository.CandidateThemeRadarTraceRepository;
import com.austin.trading.repository.HotGroupRadarSnapshotRepository;
import com.austin.trading.repository.HotGroupStockSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HotGroupRadarServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private HotGroupRadarSnapshotRepository snapshotRepo;
    private HotGroupStockSignalRepository signalRepo;
    private CandidateThemeRadarTraceRepository traceRepo;
    private HotGroupRadarService service;
    private final List<HotGroupRadarSnapshotEntity> snapshots = new ArrayList<>();
    private final List<HotGroupStockSignalEntity> signals = new ArrayList<>();
    private final List<CandidateThemeRadarTraceEntity> traces = new ArrayList<>();

    @BeforeEach
    void setUp() {
        snapshotRepo = mock(HotGroupRadarSnapshotRepository.class);
        signalRepo = mock(HotGroupStockSignalRepository.class);
        traceRepo = mock(CandidateThemeRadarTraceRepository.class);
        when(snapshotRepo.save(any())).thenAnswer(inv -> { snapshots.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(signalRepo.save(any())).thenAnswer(inv -> { signals.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(traceRepo.save(any())).thenAnswer(inv -> { traces.add(inv.getArgument(0)); return inv.getArgument(0); });
        service = new HotGroupRadarService(snapshotRepo, signalRepo, traceRepo);
    }

    @Test
    void passiveComponentTaxonomyDoesNotFallIntoOther() {
        assertThat(ThemeTaxonomyClassifier.classify("2327", "國巨*")).isEqualTo("被動元件/MLCC");
        assertThat(ThemeTaxonomyClassifier.classify("2492", "華新科")).isEqualTo("被動元件/MLCC");
        assertThat(ThemeTaxonomyClassifier.classify("3090", "日電貿")).isEqualTo("被動元件/通路代理");
        assertThat(ThemeTaxonomyClassifier.classify("2375", "凱美")).isEqualTo("被動元件/鋁電容");
        assertThat(ThemeTaxonomyClassifier.classify("2472", "立隆電")).isEqualTo("被動元件/鋁電容");
        assertThat(ThemeTaxonomyClassifier.classify("6127", "九豪")).isEqualTo("被動元件/材料設備");
    }

    @Test
    void buildFromMarketBreadthCreatesPassiveComponentGroupAndWatchOnlySignals() {
        var result = service.build(DATE, "POSTMARKET", "\uFEFF" + passiveFixtureJson());

        assertThat(result.shadowOnly()).isTrue();
        assertThat(result.observabilityOnly()).isTrue();
        assertThat(result.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
        assertThat(result.safetyBoundary().noDirectBuy()).isTrue();
        assertThat(snapshots).extracting(HotGroupRadarSnapshotEntity::getThemeTag)
                .contains("被動元件/MLCC", "被動元件/鋁電容", "被動元件/通路代理", "被動元件/材料設備");
        assertThat(signals).anySatisfy(s -> {
            assertThat(s.getSymbol()).isEqualTo("2327");
            assertThat(s.getRole()).isEqualTo("THEME_LEADER");
            assertThat(s.getTradabilityTag()).isEqualTo("WATCH_ONLY");
            assertThat(s.getCandidateAction()).isEqualTo("REJECT_LIMIT_RISK");
        });
        assertThat(signals).anySatisfy(s -> {
            assertThat(s.getSymbol()).isEqualTo("2472");
            assertThat(s.getRole()).isEqualTo("LOW_BASE_FOLLOWER");
            assertThat(s.getCandidateAction()).isEqualTo("WATCH_ONLY");
        });
        assertThat(signals).anySatisfy(s -> {
            assertThat(s.getSymbol()).isEqualTo("3090");
            assertThat(s.getRole()).isEqualTo("CHANNEL_DISTRIBUTOR");
        });
        assertThat(traces).allSatisfy(t -> {
            assertThat(t.getAppliedToCandidatePool()).isFalse();
            assertThat(t.getAppliedToFinalDecision()).isFalse();
            assertThat(t.getSafetyContractJson()).contains("noDirectBuy");
        });
    }

    @Test
    void explainMissExplainsLimitRiskAndFinalCandidateFailure() {
        service.build(DATE, "POSTMARKET", passiveFixtureJson());
        when(signalRepo.findByTradingDateAndSymbolOrderByRadarRankScoreDesc(DATE, "2375"))
                .thenReturn(signals.stream().filter(s -> "2375".equals(s.getSymbol())).toList());

        var explanation = service.explainMiss(DATE, "2375");

        assertThat(explanation.inUniverse()).isTrue();
        assertThat(explanation.inHotStock()).isTrue();
        assertThat(explanation.classifiedAsOtherBeforeRadar()).isTrue();
        assertThat(explanation.limitRisk()).isTrue();
        assertThat(explanation.finalCandidateFail()).isTrue();
        assertThat(explanation.hotGroupRadarWatchOnly()).isTrue();
        assertThat(explanation.reasons()).contains("limit_risk", "not_in_final_candidates_5", "radar_watch_only");
    }

    static String passiveFixtureJson() {
        return """
                {
                  "hot_stocks": [
                    {"Code":"2327","Name":"國巨*","Theme":"其他強勢股","ChangePct":9.97,"AmountYi":455.0,"NearHigh":1.0,"Score":24.43,"BoardLotCost":629000,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"},
                    {"Code":"2492","Name":"華新科","Theme":"其他強勢股","ChangePct":9.96,"AmountYi":31.73,"NearHigh":1.0,"Score":21.0,"BoardLotCost":292500,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"},
                    {"Code":"2375","Name":"凱美","Theme":"其他強勢股","ChangePct":9.80,"AmountYi":37.14,"NearHigh":1.0,"Score":20.99,"BoardLotCost":134500,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"},
                    {"Code":"3090","Name":"日電貿","Theme":"其他強勢股","ChangePct":9.69,"AmountYi":18.24,"NearHigh":0.9977,"Score":19.97,"BoardLotCost":215000,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"}
                  ],
                  "limit_indicators": [
                    {"Code":"2327","Name":"國巨*","Theme":"其他強勢股","ChangePct":9.97,"AmountYi":455.0,"NearHigh":1.0,"Score":24.43,"BoardLotCost":629000,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"},
                    {"Code":"2492","Name":"華新科","Theme":"其他強勢股","ChangePct":9.96,"AmountYi":31.73,"NearHigh":1.0,"Score":21.0,"BoardLotCost":292500,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"},
                    {"Code":"2375","Name":"凱美","Theme":"其他強勢股","ChangePct":9.80,"AmountYi":37.14,"NearHigh":1.0,"Score":20.99,"BoardLotCost":134500,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"}
                  ],
                  "super_strong_5": [
                    {"Code":"2327","Name":"國巨*","Theme":"其他強勢股","ChangePct":9.97,"AmountYi":455.0,"NearHigh":1.0,"Score":24.43,"BoardLotCost":629000,"IsLimitRisk":true,"TradabilityTag":"題材指標，不列主進場"}
                  ],
                  "final_candidates_5": [],
                  "tradable_pool": [
                    {"Code":"2472","Name":"立隆電","Theme":"其他強勢股","ChangePct":1.20,"AmountYi":43.93,"NearHigh":0.9594,"Score":8.0,"BoardLotCost":260000,"IsLimitRisk":false,"TradabilityTag":"觀察"},
                    {"Code":"6127","Name":"九豪","Theme":"其他強勢股","ChangePct":0.80,"AmountYi":15.95,"NearHigh":0.9477,"Score":6.0,"BoardLotCost":52500,"IsLimitRisk":false,"TradabilityTag":"觀察"}
                  ]
                }
                """;
    }
}
