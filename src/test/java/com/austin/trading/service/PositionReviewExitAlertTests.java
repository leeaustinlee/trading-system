package com.austin.trading.service;

import com.austin.trading.engine.ExitRegimeIntegrationEngine;
import com.austin.trading.engine.PositionDecisionEngine;
import com.austin.trading.engine.PositionDecisionEngine.PositionDecisionResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.engine.PositionDecisionEngine.TrailingAction;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.notify.LineSender;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P0.2:position_review_log 出現 EXIT 訊號時必須送 LINE 警示。
 */
class PositionReviewExitAlertTests {

    private PositionReviewService service;
    private LineSender lineSender;
    private ScoreConfigService scoreConfig;

    @BeforeEach
    void setUp() {
        lineSender = mock(LineSender.class);
        scoreConfig = mock(ScoreConfigService.class);
        // default: alert flag ON
        when(scoreConfig.getBoolean(eq("position.review.exit_alert.enabled"), anyBoolean()))
                .thenReturn(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<LineSender> lineSenderProvider = mock(ObjectProvider.class);
        when(lineSenderProvider.getIfAvailable()).thenReturn(lineSender);

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
                lineSenderProvider
        );
    }

    @Test
    void exitDecision_callsLineSenderOnce_withSymbolInMessage() {
        PositionEntity pos = pos("2330");
        PositionDecisionResult exitDecision = new PositionDecisionResult(
                PositionStatus.EXIT,
                "停損觸發",
                null,
                TrailingAction.NONE
        );

        service.maybeSendExitAlert(pos, exitDecision);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(lineSender, times(1)).send(msg.capture());
        org.assertj.core.api.Assertions.assertThat(msg.getValue())
                .contains("2330")
                .contains("EXIT")
                .contains("停損觸發");
    }

    @Test
    void strongDecision_doesNotCallLineSender() {
        PositionEntity pos = pos("2317");
        PositionDecisionResult strong = new PositionDecisionResult(
                PositionStatus.STRONG, "trend up", null, TrailingAction.NONE);

        service.maybeSendExitAlert(pos, strong);

        verify(lineSender, never()).send(anyString());
    }

    @Test
    void holdDecision_doesNotCallLineSender() {
        PositionDecisionResult hold = new PositionDecisionResult(
                PositionStatus.HOLD, "still ok", null, TrailingAction.NONE);
        service.maybeSendExitAlert(pos("2454"), hold);
        verify(lineSender, never()).send(anyString());
    }

    @Test
    void exitDecision_skipsWhenAlertConfigDisabled() {
        when(scoreConfig.getBoolean(eq("position.review.exit_alert.enabled"), anyBoolean()))
                .thenReturn(false);

        PositionDecisionResult exit = new PositionDecisionResult(
                PositionStatus.EXIT, "停損觸發", null, TrailingAction.NONE);

        service.maybeSendExitAlert(pos("2330"), exit);

        verify(lineSender, never()).send(anyString());
    }

    @Test
    void exitDecision_swallowsLineSenderException() {
        when(lineSender.send(anyString())).thenThrow(new RuntimeException("boom"));

        PositionDecisionResult exit = new PositionDecisionResult(
                PositionStatus.EXIT, "停損觸發", null, TrailingAction.NONE);

        // 不應該 propagate 出來
        service.maybeSendExitAlert(pos("2330"), exit);

        verify(lineSender, times(1)).send(anyString());
    }

    private PositionEntity pos(String symbol) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        return p;
    }
}
