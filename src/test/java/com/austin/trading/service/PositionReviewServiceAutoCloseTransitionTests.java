package com.austin.trading.service;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.engine.ExitRegimeIntegrationEngine;
import com.austin.trading.engine.PositionDecisionEngine;
import com.austin.trading.engine.PositionDecisionEngine.PositionDecisionResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.engine.PositionDecisionEngine.TrailingAction;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.notify.LineSender;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0.3 NEEDS_FIX 修補：transition gate 必須要求 prev != null。
 *
 * <p>原本的 gate 只擋 prev == "EXIT"，所以 {@code prev=null}（首次審查、{@code last_reviewed_at IS NULL}）
 * 時 {@code "EXIT".equalsIgnoreCase(null) == false}，會誤判為「轉換」→ 首次審查就觸發 auto-close。
 * shadow 階段（paper_only=true）無害，但 5/5 SQL 把 paper_only 切 false 那一刻，
 * 所有現存 OPEN position（review_status 仍 NULL）會被一次刷掉。</p>
 *
 * <p>這裡測 5 個 prev × curr 組合，確認新 gate {@code prev != null && prev != EXIT && curr == EXIT} 行為正確。</p>
 *
 * <p>注意：
 * <ul>
 *   <li>{@link #maybeAutoClosePosition} 不影響 {@link #maybeSendExitAlert}（後者 prev=null 仍送 alert，是設計）。</li>
 *   <li>{@code paper_only=true}（shadow，預設）：「觸發」= paper_trade 被 close + LINE shadow。真倉永遠不動。</li>
 * </ul>
 * </p>
 */
class PositionReviewServiceAutoCloseTransitionTests {

    private PositionReviewService service;
    private LineSender lineSender;
    private ScoreConfigService scoreConfig;
    private PaperTradeService paperTradeService;
    private PaperTradeRepository paperTradeRepository;
    private PositionService positionService;

    @BeforeEach
    void setUp() {
        lineSender           = mock(LineSender.class);
        scoreConfig          = mock(ScoreConfigService.class);
        paperTradeService    = mock(PaperTradeService.class);
        paperTradeRepository = mock(PaperTradeRepository.class);
        positionService      = mock(PositionService.class);

        when(scoreConfig.getBoolean(eq("position.review.exit_alert.enabled"), anyBoolean())).thenReturn(true);
        when(scoreConfig.getBoolean(eq("position.review.auto_close.enabled"), anyBoolean())).thenReturn(true);
        when(scoreConfig.getBoolean(eq("position.review.auto_close.paper_only"), anyBoolean())).thenReturn(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<LineSender> lineSenderProvider = mock(ObjectProvider.class);
        when(lineSenderProvider.getIfAvailable()).thenReturn(lineSender);

        @SuppressWarnings("unchecked")
        ObjectProvider<PaperTradeService> paperSvcProvider = mock(ObjectProvider.class);
        when(paperSvcProvider.getIfAvailable()).thenReturn(paperTradeService);

        @SuppressWarnings("unchecked")
        ObjectProvider<PaperTradeRepository> paperRepoProvider = mock(ObjectProvider.class);
        when(paperRepoProvider.getIfAvailable()).thenReturn(paperTradeRepository);

        @SuppressWarnings("unchecked")
        ObjectProvider<PositionService> posSvcProvider = mock(ObjectProvider.class);
        when(posSvcProvider.getIfAvailable()).thenReturn(positionService);

        service = new PositionReviewService(
                mock(PositionRepository.class),
                mock(PositionReviewLogRepository.class),
                mock(PositionDecisionEngine.class),
                mock(ExitRegimeIntegrationEngine.class),
                mock(CandidateScanService.class),
                mock(StopLossTakeProfitEngine.class),
                scoreConfig,
                mock(MarketRegimeService.class),
                mock(ThemeStrengthService.class),
                lineSenderProvider,
                paperSvcProvider,
                paperRepoProvider,
                posSvcProvider
        );
    }

    /**
     * Case 1：prev=null + curr=EXIT
     * 首次審查的 OPEN position，review_status 還沒有任何 prior 值。
     * 必須 **不觸發** auto-close（避免 5/5 切真倉那刻 mass-close 既有持倉）。
     */
    @Test
    void prevNull_currExit_doesNotTriggerAutoClose() {
        PositionEntity pos = pos(101L, "2330", null);  // 首次審查
        // 即使 paper trade 存在，也不該被動到
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN"))
                .thenReturn(List.of(paperTrade(7L, "2330")));

        service.maybeAutoClosePosition(pos, exitDecision("score=EXIT 但首次審查"), quote(620.0));

        verify(paperTradeService, never()).closeTradeFromAutoExit(any(), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));
        verify(lineSender, never()).send(anyString());
    }

    /**
     * Case 2：prev=HOLD + curr=EXIT — 標準 transition，**必須觸發** auto-close。
     */
    @Test
    void prevHold_currExit_triggersAutoClose() {
        PositionEntity pos = pos(102L, "2317", "HOLD");
        PaperTradeEntity paper = paperTrade(8L, "2317");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2317", "OPEN"))
                .thenReturn(List.of(paper));

        service.maybeAutoClosePosition(pos, exitDecision("跌破 5 日線"), quote(180.0));

        verify(paperTradeService, times(1)).closeTradeFromAutoExit(eq(paper), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));  // shadow
        verify(lineSender, times(1)).send(anyString());
    }

    /**
     * Case 3：prev=EXIT + curr=EXIT — 已經 exit 過了，**不觸發**（避免 5-min monitor 反覆動）。
     */
    @Test
    void prevExit_currExit_doesNotTrigger_dedupe() {
        PositionEntity pos = pos(103L, "2454", "EXIT");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2454", "OPEN"))
                .thenReturn(List.of(paperTrade(9L, "2454")));

        service.maybeAutoClosePosition(pos, exitDecision("仍弱"), quote(1300.0));

        verify(paperTradeService, never()).closeTradeFromAutoExit(any(), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));
        verify(lineSender, never()).send(anyString());
    }

    /**
     * Case 4：prev=null + curr=HOLD — 首次審查 + 非 EXIT，**不觸發**（auto-close 本來就只看 EXIT）。
     */
    @Test
    void prevNull_currHold_doesNotTrigger() {
        PositionEntity pos = pos(104L, "2603", null);

        service.maybeAutoClosePosition(pos, holdDecision("還在守 5 日線"), quote(45.0));

        verify(paperTradeService, never()).closeTradeFromAutoExit(any(), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));
        verify(lineSender, never()).send(anyString());
    }

    /**
     * Case 5：prev=WEAKEN + curr=EXIT — 從 WEAKEN 進一步惡化到 EXIT，**必須觸發**。
     */
    @Test
    void prevWeaken_currExit_triggersAutoClose() {
        PositionEntity pos = pos(105L, "8112", "WEAKEN");
        PaperTradeEntity paper = paperTrade(11L, "8112");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("8112", "OPEN"))
                .thenReturn(List.of(paper));

        service.maybeAutoClosePosition(pos, exitDecision("WEAKEN → EXIT"), quote(56.4));

        verify(paperTradeService, times(1)).closeTradeFromAutoExit(eq(paper), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));
        verify(lineSender, times(1)).send(anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private PositionEntity pos(Long id, String symbol, String prevReviewStatus) {
        PositionEntity p = new PositionEntity();
        forceId(p, "id", id);
        p.setSymbol(symbol);
        p.setStatus("OPEN");
        p.setReviewStatus(prevReviewStatus);
        return p;
    }

    private PaperTradeEntity paperTrade(Long id, String symbol) {
        PaperTradeEntity t = new PaperTradeEntity();
        forceId(t, "id", id);
        t.setSymbol(symbol);
        t.setStatus("OPEN");
        t.setEntryDate(java.time.LocalDate.now().minusDays(2));
        t.setEntryPrice(new BigDecimal("100.0"));
        t.setSimulatedEntryPrice(new BigDecimal("100.1"));
        t.setPositionShares(1000);
        return t;
    }

    private static void forceId(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private PositionDecisionResult exitDecision(String reason) {
        return new PositionDecisionResult(PositionStatus.EXIT, reason, null, TrailingAction.NONE);
    }

    private PositionDecisionResult holdDecision(String reason) {
        return new PositionDecisionResult(PositionStatus.HOLD, reason, null, TrailingAction.NONE);
    }

    private LiveQuoteResponse quote(double currentPrice) {
        return new LiveQuoteResponse(
                "TEST", null, "tse",
                currentPrice, currentPrice, currentPrice, currentPrice, currentPrice,
                null, null, null, null, true);
    }
}
