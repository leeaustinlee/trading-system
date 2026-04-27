package com.austin.trading.notify;

import com.austin.trading.dto.response.FinalDecisionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.*;

/**
 * v2.13 NotificationFacade 並聯 LINE + Telegram 測試。
 */
class NotificationFacadeTests {

    private TelegramTemplateService telegram;
    private LineTemplateService line;
    private NotificationFacade facade;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramTemplateService.class);
        line = mock(LineTemplateService.class);
        facade = new NotificationFacade(telegram, line);
    }

    @Test
    void notifyFinalDecision_callsBothChannels() {
        FinalDecisionResponse decision = new FinalDecisionResponse("ENTER", java.util.List.of(), java.util.List.of(), "", null);
        LocalDate date = LocalDate.now();
        facade.notifyFinalDecision(decision, date);
        verify(telegram).notifyFinalDecision(decision, date);
        verify(line).notifyFinalDecision(decision, date);
    }

    @Test
    void notifyPremarket_callsBothChannels() {
        facade.notifyPremarket("market", "candidates", LocalDate.now());
        verify(telegram).notifyPremarket(eq("market"), eq("candidates"), any());
        verify(line).notifyPremarket(eq("market"), eq("candidates"), any());
    }

    @Test
    void telegramFailure_doesNotPreventLine() {
        doThrow(new RuntimeException("tg down")).when(telegram).notifySystemAlert(any(), any());
        // 不應丟例外
        facade.notifySystemAlert("title", "msg");
        verify(line).notifySystemAlert("title", "msg");
    }

    @Test
    void lineFailure_doesNotPreventTelegram() {
        doThrow(new RuntimeException("line down")).when(line).notifySystemAlert(any(), any());
        facade.notifySystemAlert("title", "msg");
        verify(telegram).notifySystemAlert("title", "msg");
    }

    @Test
    void notifyPositionAction_threeArgsForwardsToBoth() {
        facade.notifyPositionAction("2330", "ADD", "reason", 100.0, 90.0, 11.0,
                java.util.List.of("sig"), null, null);
        verify(telegram).notifyPositionAction(eq("2330"), eq("ADD"), eq("reason"),
                eq(100.0), eq(90.0), eq(11.0), any(), eq((String) null), eq((Double) null),
                eq((Double) null), eq((Integer) null), eq((Double) null));
        verify(line).notifyPositionAction(eq("2330"), eq("ADD"), eq("reason"),
                eq(100.0), eq(90.0), eq(11.0), any(), eq((String) null), eq((Double) null),
                eq((Double) null), eq((Integer) null), eq((Double) null));
    }
}
