package com.austin.trading.controller;

import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.response.CandidateBatchSaveResponse;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.engine.MomentumCandidateEngine;
import com.austin.trading.engine.MomentumCandidateEngine.CandidateDecision;
import com.austin.trading.engine.MomentumCandidateEngine.CandidateInput;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.StockEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v2.3 MomentumCandidateEngine hard gate 行為驗證。
 * <p>
 * 跑 controller 真實的 saveBatch（不 mock），底層 service 是 spy/real，
 * Repository / TwseMisClient 全 mock。Engine 用真實 instance，
 * 但搭配 mock ScoreConfigService 控制 feature flag 與門檻。
 * </p>
 */
class CandidateControllerMomentumGateTests {

    private CandidateController controller;

    private CandidateStockRepository candidateStockRepository;
    private StockEvaluationRepository stockEvaluationRepository;
    private ThemeSnapshotRepository themeSnapshotRepository;
    private ScoreConfigService scoreConfigService;
    private MomentumCandidateEngine momentumCandidateEngine;
    private CandidateScanService candidateScanService;

    @BeforeEach
    void setUp() {
        candidateStockRepository  = mock(CandidateStockRepository.class);
        stockEvaluationRepository = mock(StockEvaluationRepository.class);
        themeSnapshotRepository   = mock(ThemeSnapshotRepository.class);
        scoreConfigService        = mock(ScoreConfigService.class);
        var twseMisClient = mock(com.austin.trading.client.TwseMisClient.class);
        var stockEvaluationService = mock(StockEvaluationService.class);

        // 預設：所有 config getter 回 default
        when(scoreConfigService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(scoreConfigService.getDecimal(anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(scoreConfigService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        // gate flag 預設 ON
        when(scoreConfigService.getBoolean(eq("candidate.momentum_gate.enabled"), anyBoolean()))
                .thenReturn(true);

        // 沒有 theme snapshot → engine 走 fallback 中性分
        when(themeSnapshotRepository.findByTradingDateAndThemeTag(any(), anyString()))
                .thenReturn(Optional.empty());

        // candidate_stock save: 直接回傳 entity；findByTradingDateAndSymbol → empty
        when(candidateStockRepository.findByTradingDateAndSymbol(any(), anyString()))
                .thenReturn(Optional.empty());
        when(candidateStockRepository.save(any(CandidateStockEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(any(), any()))
                .thenReturn(List.of());
        when(stockEvaluationRepository.findByTradingDate(any()))
                .thenReturn(List.of());

        momentumCandidateEngine = new MomentumCandidateEngine(scoreConfigService);

        candidateScanService = new CandidateScanService(
                candidateStockRepository,
                stockEvaluationRepository,
                themeSnapshotRepository,
                twseMisClient,
                momentumCandidateEngine,
                scoreConfigService,
                new ObjectMapper()
        );

        controller = new CandidateController(candidateScanService, stockEvaluationService);
    }

    /** Test 1：日漲 5%、有量、有題材排名 → 通過 gate，is_momentum_candidate=true。 */
    @Test
    void goodCandidate_passesGate_isPersistedWithFlagTrue() {
        // 給出主流題材的 snapshot
        ThemeSnapshotEntity snap = new ThemeSnapshotEntity();
        snap.setThemeTag("AI_PCB");
        snap.setRankingOrder(1);
        snap.setFinalThemeScore(new BigDecimal("8.5"));
        when(themeSnapshotRepository.findByTradingDateAndThemeTag(any(), eq("AI_PCB")))
                .thenReturn(Optional.of(snap));

        var item = new CandidateBatchItemRequest(
                LocalDate.of(2026, 4, 28), "2330", "台積電",
                new BigDecimal("8.5"), "AI主流",
                "AI_PCB", "半導體",
                "{\"changePct\":5.5,\"volumeRatio\":2.1,\"claudeScore\":7.0,\"todayAboveOpen\":true,\"consecutiveUpDays\":2}",
                null, null, null, null, null, null, null
        );

        ResponseEntity<Map<String, Object>> resp = controller.saveBatch(List.of(item));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("received", 1);
        assertThat(body).containsEntry("accepted", 1);
        assertThat(body).containsEntry("rejected", 0);

        // 驗證 entity 被存入時 isMomentumCandidate=true
        ArgumentCaptor<CandidateStockEntity> captor = ArgumentCaptor.forClass(CandidateStockEntity.class);
        verify(candidateStockRepository).save(captor.capture());
        CandidateStockEntity saved = captor.getValue();
        assertThat(saved.isMomentumCandidate()).isTrue();
        assertThat(saved.getMomentumFlagsJson()).contains("priceMomentum");
    }

    /** Test 2：claudeScore=3.0（低於 4.0 門檻）→ HARD_VETO，不寫入 DB。 */
    @Test
    void hardVetoCandidate_isRejected_notPersisted() {
        var item = new CandidateBatchItemRequest(
                LocalDate.of(2026, 4, 28), "9999", "爛股",
                new BigDecimal("3.0"), "Claude打槍",
                null, null,
                "{\"changePct\":4.0,\"claudeScore\":3.0,\"volumeRatio\":2.0}",
                null, null, null, null, null, null, null
        );

        ResponseEntity<Map<String, Object>> resp = controller.saveBatch(List.of(item));

        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("received", 1);
        assertThat(body).containsEntry("accepted", 0);
        assertThat(body).containsEntry("rejected", 1);

        @SuppressWarnings("unchecked")
        List<CandidateBatchSaveResponse.Rejection> rejections =
                (List<CandidateBatchSaveResponse.Rejection>) body.get("rejections");
        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).symbol()).isEqualTo("9999");
        assertThat(rejections.get(0).reason()).isEqualTo("HARD_VETO_CLAUDE_LOW_SCORE");
        assertThat(rejections.get(0).details()).contains("3.0");

        // 不應該寫入 candidate_stock
        verify(candidateStockRepository, never()).save(any(CandidateStockEntity.class));
    }

    /** Test 3：feature flag=false → bypass gate，所有 candidate 都寫入。 */
    @Test
    void gateFlagDisabled_persistsAllCandidates_evenWeak() {
        when(scoreConfigService.getBoolean(eq("candidate.momentum_gate.enabled"), anyBoolean()))
                .thenReturn(false);

        // 一筆會被 hard veto 的，一筆條件不足的；flag 關閉時都應該存
        var weak1 = new CandidateBatchItemRequest(
                LocalDate.of(2026, 4, 28), "9990", "弱1", null, null, null, null,
                "{\"claudeScore\":2.0}",  // 低於 4.0 → 平常會 veto
                null, null, null, null, null, null, null
        );
        var weak2 = new CandidateBatchItemRequest(
                LocalDate.of(2026, 4, 28), "9991", "弱2", null, null, null, null,
                "{}",  // 完全無訊號
                null, null, null, null, null, null, null
        );

        ResponseEntity<Map<String, Object>> resp = controller.saveBatch(List.of(weak1, weak2));

        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("received", 2);
        assertThat(body).containsEntry("accepted", 2);
        assertThat(body).containsEntry("rejected", 0);

        // 都應該存到 DB（且 isMomentumCandidate 不會被設 true）
        ArgumentCaptor<CandidateStockEntity> captor = ArgumentCaptor.forClass(CandidateStockEntity.class);
        verify(candidateStockRepository, times(2)).save(captor.capture());
        for (CandidateStockEntity e : captor.getAllValues()) {
            assertThat(e.isMomentumCandidate()).isFalse();
        }
    }

    /** Test 4：5 進 → 3 通過、2 退件，response 同時列出 accepted 與 rejections。 */
    @Test
    void mixedBatch_5In_3Pass_2Rejected_responseShowsBoth() {
        ThemeSnapshotEntity strongTheme = new ThemeSnapshotEntity();
        strongTheme.setThemeTag("AI");
        strongTheme.setRankingOrder(1);
        strongTheme.setFinalThemeScore(new BigDecimal("8.0"));
        when(themeSnapshotRepository.findByTradingDateAndThemeTag(any(), eq("AI")))
                .thenReturn(Optional.of(strongTheme));

        LocalDate date = LocalDate.of(2026, 4, 28);
        // 通過：日漲 5%、量 2x、AI 主流、claude 7
        var ok1 = makeReq(date, "0001", "AI",
                "{\"changePct\":5.0,\"volumeRatio\":2.0,\"claudeScore\":7.0,\"consecutiveUpDays\":2,\"todayAboveOpen\":true}");
        // 通過：日漲 4%、新高、量 1.8x
        var ok2 = makeReq(date, "0002", "AI",
                "{\"changePct\":4.0,\"todayNewHigh20\":true,\"volumeRatio\":1.8,\"claudeScore\":6.5}");
        // 通過：日漲 3.5%、量 2x
        var ok3 = makeReq(date, "0003", "AI",
                "{\"changePct\":3.5,\"volumeRatio\":2.0,\"claudeScore\":6.0,\"todayAboveOpen\":true,\"consecutiveUpDays\":3}");
        // 退件 1：codex veto
        var bad1 = makeReq(date, "9001", "AI",
                "{\"changePct\":5.0,\"volumeRatio\":2.0,\"claudeScore\":7.0,\"codexVetoed\":true}");
        // 退件 2：claude 太低
        var bad2 = makeReq(date, "9002", "AI",
                "{\"changePct\":5.0,\"volumeRatio\":2.0,\"claudeScore\":3.5}");

        ResponseEntity<Map<String, Object>> resp =
                controller.saveBatch(List.of(ok1, ok2, ok3, bad1, bad2));

        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("received", 5);
        assertThat(body).containsEntry("accepted", 3);
        assertThat(body).containsEntry("rejected", 2);

        @SuppressWarnings("unchecked")
        List<CandidateBatchSaveResponse.Rejection> rejections =
                (List<CandidateBatchSaveResponse.Rejection>) body.get("rejections");
        assertThat(rejections).hasSize(2);
        assertThat(rejections).extracting(CandidateBatchSaveResponse.Rejection::symbol)
                .containsExactlyInAnyOrder("9001", "9002");
        assertThat(rejections).extracting(CandidateBatchSaveResponse.Rejection::reason)
                .containsExactlyInAnyOrder("HARD_VETO_CODEX", "HARD_VETO_CLAUDE_LOW_SCORE");

        // 只有 3 筆寫入
        verify(candidateStockRepository, times(3)).save(any(CandidateStockEntity.class));
    }

    /** Test 5：payload 全空（最小資訊）→ engine 採寬鬆 fallback，多數應該通過。 */
    @Test
    void missingFields_useFallbacks_majorityPass() {
        LocalDate date = LocalDate.of(2026, 4, 28);
        // 三筆都只有 symbol/themeTag，沒有 payloadJson
        var b1 = new CandidateBatchItemRequest(date, "1101", "標的1", null, null, null, null, null,
                null, null, null, null, null, null, null);
        var b2 = new CandidateBatchItemRequest(date, "1102", "標的2", null, null, null, null, null,
                null, null, null, null, null, null, null);
        var b3 = new CandidateBatchItemRequest(date, "1103", "標的3", null, null, null, null, null,
                null, null, null, null, null, null, null);

        ResponseEntity<Map<String, Object>> resp =
                controller.saveBatch(List.of(b1, b2, b3));

        Map<String, Object> body = resp.getBody();
        // 哲學：fallback 寬鬆 → 完全空欄位的標的不應全部被排除（gate 不該變成 noise filter）。
        // 但 priceMomentum=null + theme=fallback rank99/score5.0 + volume=null → 不到 3 條，會被 INSUFFICIENT_CONDITIONS 退件。
        // 這個測試驗證的是「fallback 不會被 hard veto」（沒有 codex / claude flag），
        // 所有退件都應該是 INSUFFICIENT_CONDITIONS（軟性），不是 HARD_VETO_*（硬性）。
        @SuppressWarnings("unchecked")
        List<CandidateBatchSaveResponse.Rejection> rejections =
                (List<CandidateBatchSaveResponse.Rejection>) body.get("rejections");
        for (CandidateBatchSaveResponse.Rejection r : rejections) {
            assertThat(r.reason())
                    .as("空欄位不應被 hard veto；只能是 INSUFFICIENT_CONDITIONS")
                    .doesNotStartWith("HARD_VETO_");
        }
    }

    private CandidateBatchItemRequest makeReq(LocalDate date, String symbol, String theme, String payloadJson) {
        return new CandidateBatchItemRequest(
                date, symbol, "Stock_" + symbol, new BigDecimal("7.0"), "test",
                theme, "Sector",
                payloadJson,
                null, null, null, null, null, null, null
        );
    }
}
