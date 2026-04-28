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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 * P0.3:position_review_log 出現 EXIT 訊號時，必須自動寫一筆 paper_trade 平倉。
 *
 * <p>5 日 shadow window 內（{@code position.review.auto_close.paper_only=true}）只動 paper_trade，
 * 不動真倉，但 LINE 送 shadow 文案。5 日後 SQL 把 flag 切 false，才動 PositionService.close。</p>
 *
 * <p>Transition gate（{@code prev != EXIT && curr == EXIT}）沿用昨天 maybeSendExitAlert 的 dedupe pattern，
 * 防止 5-min monitor 反覆觸發。</p>
 */
class PositionReviewExitAutoCloseTests {

    private PositionReviewService service;
    private LineSender lineSender;
    private ScoreConfigService scoreConfig;
    private PaperTradeService paperTradeService;
    private PaperTradeRepository paperTradeRepository;
    private PositionService positionService;

    @BeforeEach
    void setUp() {
        lineSender         = mock(LineSender.class);
        scoreConfig        = mock(ScoreConfigService.class);
        paperTradeService  = mock(PaperTradeService.class);
        paperTradeRepository = mock(PaperTradeRepository.class);
        positionService    = mock(PositionService.class);

        // defaults: alert + auto_close enabled, paper_only TRUE (shadow mode)
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
     * Shadow mode（預設）：transition non-EXIT → EXIT 時，paper_trade 必須被平倉，但真倉不動。
     * LINE 送 "[Auto-close shadow] ..."。
     */
    @Test
    void transitionToExit_paperOnly_paperClosed_realUntouched() {
        PositionEntity pos = pos(101L, "2330", "HOLD");  // prev != EXIT
        PaperTradeEntity paper = paperTrade(7L, "2330");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN"))
                .thenReturn(List.of(paper));

        service.maybeAutoClosePosition(pos, exitDecision("trailing 跌破"), quote(620.5));

        // paper_trade 被平倉一次
        ArgumentCaptor<PaperTradeService.ExitResult> exitCap =
                ArgumentCaptor.forClass(PaperTradeService.ExitResult.class);
        verify(paperTradeService, times(1)).closeTradeFromAutoExit(eq(paper), exitCap.capture());
        assertThat(exitCap.getValue().exitReason()).isEqualTo("POSITION_REVIEW_EXIT");
        assertThat(exitCap.getValue().exitPrice()).isEqualByComparingTo("620.5");

        // 真倉不能被動到
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));

        // LINE shadow 文案
        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(lineSender, times(1)).send(msgCap.capture());
        assertThat(msgCap.getValue())
                .contains("[Auto-close shadow]")
                .contains("2330")
                .contains("paper recorded");
    }

    /**
     * Dedupe：上一輪已是 EXIT、本輪仍 EXIT，不能重複動作。
     * （5 分鐘 monitor 一檔 EXIT 一天會跑 ~54 次，不 dedupe 會炸）
     */
    @Test
    void repeatedExit_noNewActions_dedup() {
        PositionEntity pos = pos(101L, "2330", "EXIT");  // prev == EXIT
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN"))
                .thenReturn(List.of(paperTrade(7L, "2330")));

        service.maybeAutoClosePosition(pos, exitDecision("仍弱"), quote(615.0));

        verify(paperTradeService, never()).closeTradeFromAutoExit(any(), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));
        verify(lineSender, never()).send(anyString());
    }

    /**
     * paper_only=false（5 日後切真倉）：paper + real 都要平倉，LINE 送成功文案。
     */
    @Test
    void transitionToExit_paperOnlyFalse_bothPaperAndRealClosed() {
        when(scoreConfig.getBoolean(eq("position.review.auto_close.paper_only"), anyBoolean())).thenReturn(false);

        PositionEntity pos = pos(101L, "8112", "HOLD");
        PaperTradeEntity paper = paperTrade(9L, "8112");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("8112", "OPEN"))
                .thenReturn(List.of(paper));
        when(positionService.close(anyLong(), any(PositionCloseRequest.class))).thenReturn(null);

        service.maybeAutoClosePosition(pos, exitDecision("EXIT"), quote(56.4));

        verify(paperTradeService, times(1)).closeTradeFromAutoExit(eq(paper), any(PaperTradeService.ExitResult.class));

        ArgumentCaptor<PositionCloseRequest> closeCap = ArgumentCaptor.forClass(PositionCloseRequest.class);
        verify(positionService, times(1)).close(eq(101L), closeCap.capture());
        assertThat(closeCap.getValue().closePrice()).isEqualByComparingTo("56.4");
        assertThat(closeCap.getValue().exitReason()).isEqualTo("POSITION_REVIEW_EXIT");

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(lineSender, times(1)).send(msgCap.capture());
        assertThat(msgCap.getValue())
                .contains("[Auto-close OK]")
                .contains("8112");
    }

    /**
     * Kill switch：position.review.auto_close.enabled=false → 啥都不做。
     */
    @Test
    void flagDisabled_noActions() {
        when(scoreConfig.getBoolean(eq("position.review.auto_close.enabled"), anyBoolean())).thenReturn(false);

        PositionEntity pos = pos(101L, "2330", "HOLD");
        service.maybeAutoClosePosition(pos, exitDecision("EXIT"), quote(620.0));

        verify(paperTradeService, never()).closeTradeFromAutoExit(any(), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));
        verify(lineSender, never()).send(anyString());
    }

    /**
     * 沒有對應的 OPEN paper_trade（只買真倉、沒有 paper 對應）：不能炸；shadow LINE 仍要送，
     * 真倉在 shadow mode 不動。
     */
    @Test
    void noMatchingPaperTrade_logsButNoCrash() {
        PositionEntity pos = pos(101L, "9999", "HOLD");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("9999", "OPEN"))
                .thenReturn(new ArrayList<>());

        service.maybeAutoClosePosition(pos, exitDecision("EXIT"), quote(50.0));

        verify(paperTradeService, never()).closeTradeFromAutoExit(any(), any());
        verify(positionService, never()).close(anyLong(), any(PositionCloseRequest.class));

        // LINE shadow 仍會送，但訊息註明「no matching paper trade」
        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(lineSender, times(1)).send(msgCap.capture());
        assertThat(msgCap.getValue())
                .contains("[Auto-close shadow]")
                .contains("9999")
                .contains("no matching paper trade");
    }

    /**
     * paper_only=false 路徑下，PositionService.close 成功 → LINE 文案要包含 "[Auto-close OK]" 與 symbol。
     */
    @Test
    void realCloseSucceeds_lineAlertSentWithCorrectMsg() {
        when(scoreConfig.getBoolean(eq("position.review.auto_close.paper_only"), anyBoolean())).thenReturn(false);

        PositionEntity pos = pos(101L, "2454", "WEAKEN");  // prev != EXIT
        PaperTradeEntity paper = paperTrade(11L, "2454");
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2454", "OPEN"))
                .thenReturn(List.of(paper));
        when(positionService.close(anyLong(), any(PositionCloseRequest.class))).thenReturn(null);

        service.maybeAutoClosePosition(pos, exitDecision("跌破均價"), quote(1320.0));

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(lineSender, times(1)).send(msgCap.capture());
        assertThat(msgCap.getValue())
                .contains("[Auto-close OK]")
                .contains("2454")
                .contains("已自動平倉");
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

    /** entity 的 id 是 @GeneratedValue 沒 setter；測試用反射塞值。 */
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

    private LiveQuoteResponse quote(double currentPrice) {
        return new LiveQuoteResponse(
                "TEST", null, "tse",
                currentPrice, currentPrice, currentPrice, currentPrice, currentPrice,
                null, null, null, null, true);
    }
}
