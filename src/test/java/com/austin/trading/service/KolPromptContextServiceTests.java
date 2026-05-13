package com.austin.trading.service;

import com.austin.trading.dto.response.KolThemeContextResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KolPromptContextServiceTests {

    @Test
    void promptContext_containsWeakSignalGuardrail() {
        KolSignalContextService contextService = mock(KolSignalContextService.class);
        LocalDate date = LocalDate.of(2026, 5, 13);
        when(contextService.getContext(date)).thenReturn(new KolThemeContextResponse(
                date,
                KolSignalContextService.WEAK_SIGNAL_GUARDRAIL,
                List.of(new KolThemeContextResponse.ThemeContext("AI", "POSITIVE", new BigDecimal("0.2"), "LOW", 1, 1))
        ));

        KolPromptContextService service = new KolPromptContextService(contextService, new ObjectMapper());
        String json = service.jsonReadyContextString(date);

        assertThat(service.guardrailText()).contains("weak external context only");
        assertThat(json).contains("weak_signal_only");
        assertThat(json).contains("FinalDecisionEngine live ENTER conditions");
    }
}
