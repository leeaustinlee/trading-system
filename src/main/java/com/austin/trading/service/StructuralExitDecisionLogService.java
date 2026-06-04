package com.austin.trading.service;

import com.austin.trading.engine.ExitArbiterInput;
import com.austin.trading.engine.StructureAwareExitDecision;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class StructuralExitDecisionLogService {
    private static final Logger log = LoggerFactory.getLogger(StructuralExitDecisionLogService.class);
    private final StructuralExitDecisionLogRepository repository;
    private final ObjectMapper objectMapper;

    public StructuralExitDecisionLogService(StructuralExitDecisionLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveShadowLog(StructuralExitDecisionLogEntity row) {
        try {
            if (row.getEvaluatedAt() == null) row.setEvaluatedAt(LocalDateTime.now());
            if (row.getEvaluationDate() == null && row.getEvaluatedAt() != null) row.setEvaluationDate(row.getEvaluatedAt().toLocalDate());
            row.setMode("LIVE");
            row.setAutoSellEnabled(false);
            repository.save(row);
        } catch (Exception ex) {
            log.warn("Structural exit shadow log skipped; production decision is unchanged. symbol={} ref={}: {}",
                    row == null ? null : row.getSymbol(), row == null ? null : row.getTradeRefId(), ex.toString());
        }
    }

    public void saveShadowLog(ExitArbiterInput input, StructureAwareExitDecision decision) {
        if (input == null || decision == null) return;
        PositionEntity p = input.position();
        StructuralExitDecisionLogEntity row = new StructuralExitDecisionLogEntity();
        row.setTradeRefType(input.tradeRefType() == null ? "POSITION" : input.tradeRefType());
        row.setTradeRefId(input.tradeRefId() != null ? input.tradeRefId() : (p == null ? null : p.getId()));
        row.setSymbol(p == null ? null : p.getSymbol());
        row.setEvaluatedAt(LocalDateTime.now());
        row.setEvaluationDate(LocalDate.now());
        row.setSourceDecisionStatus(input.sourceDecision() == null || input.sourceDecision().status() == null ? null : input.sourceDecision().status().name());
        row.setSourceExitReason(input.sourceDecision() == null ? null : input.sourceDecision().reason());
        row.setArbiterTier(decision.tier().name());
        row.setArbiterReason(decision.reason());
        row.setRiskBlock(decision.riskBlock());
        row.setManualConfirmRequired(decision.manualConfirmRequired());
        row.setAutoSellEnabled(false);
        row.setThemeState(decision.themeState().name());
        row.setThemeStage(input.themeStage());
        row.setThemeRank(input.themeRank());
        row.setThemeScore(input.themeScore());
        row.setMainstreamTheme(input.mainstreamTheme());
        row.setStructureState(decision.structureState().name());
        row.setHealthScore(input.healthScore());
        row.setStructureStatus(input.structureStatus());
        row.setVolumeStatus(input.volumeStatus());
        row.setRelativeStrengthStatus(input.relativeStrengthStatus());
        row.setChipStatus(input.chipStatus());
        row.setPriceState(decision.priceState().name());
        row.setCurrentPrice(input.currentPrice());
        row.setEntryPrice(input.entryPrice());
        row.setHardStopPrice(input.hardStopPrice());
        row.setTrailingStopPrice(input.trailingStopPrice());
        row.setDynamicStopPrice(input.dynamicStopPrice());
        row.setMa5(input.ma5()); row.setMa10(input.ma10()); row.setMa20(input.ma20());
        row.setPreviousLow(input.previousLow()); row.setRecentHigh(input.recentHigh()); row.setAtr(input.atr());
        row.setPriceTriggerJson(json(Map.of("priceState", decision.priceState().name())));
        row.setLayerVotesJson(json(decision.layerVotes()));
        row.setDataGapsJson(json(decision.dataGaps()));
        row.setReasonJson(json(Map.of("reason", decision.reason(), "signals", decision.signals())));
        saveShadowLog(row);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
