package com.austin.trading.service;

import com.austin.trading.dto.response.KolThemeContextResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

@Service
public class KolPromptContextService {

    private final KolSignalContextService contextService;
    private final ObjectMapper objectMapper;

    public KolPromptContextService(KolSignalContextService contextService, ObjectMapper objectMapper) {
        this.contextService = contextService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> jsonReadyContext(LocalDate date) {
        KolThemeContextResponse context = contextService.getContext(date);
        return Map.of(
                "guardrail", context.guardrail(),
                "weak_signal_only", true,
                "live_decision_integration", "disabled",
                "themes", context.themes()
        );
    }

    public String guardrailText() {
        return KolSignalContextService.WEAK_SIGNAL_GUARDRAIL;
    }

    public String jsonReadyContextString(LocalDate date) {
        try {
            return objectMapper.writeValueAsString(jsonReadyContext(date));
        } catch (Exception e) {
            return "{\"weak_signal_only\":true,\"live_decision_integration\":\"disabled\"}";
        }
    }
}
