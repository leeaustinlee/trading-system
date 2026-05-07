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
    void notifyAiTaskFinal_goesTelegramOnlyToAvoidRawMarkdownFallback() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        facade.notifyAiTaskFinal("POSTMARKET", "raw markdown", date);
        verify(telegram).notifyAiTaskFinal("POSTMARKET", "raw markdown", date);
        verify(line, never()).notifyAiTaskFinal(any(), any(), any());
    }
}
