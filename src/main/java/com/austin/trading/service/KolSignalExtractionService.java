package com.austin.trading.service;

import com.austin.trading.dto.request.KolStructuredResultRequest;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.austin.trading.entity.KolThemeStockMappingEntity;
import com.austin.trading.entity.ThemeSignalEvidenceEntity;
import com.austin.trading.engine.KolThemeSignalEngine;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.austin.trading.repository.KolThemeStockMappingRepository;
import com.austin.trading.repository.ThemeSignalEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KolSignalExtractionService {

    private final KolThemeSignalRepository signalRepo;
    private final KolThemeStockMappingRepository mappingRepo;
    private final ThemeSignalEvidenceRepository evidenceRepo;
    private final KolSignalTraceService traceService;
    private final ObjectMapper objectMapper;

    public KolSignalExtractionService(KolThemeSignalRepository signalRepo,
                                      KolThemeStockMappingRepository mappingRepo,
                                      ThemeSignalEvidenceRepository evidenceRepo,
                                      KolSignalTraceService traceService,
                                      ObjectMapper objectMapper) {
        this.signalRepo = signalRepo;
        this.mappingRepo = mappingRepo;
        this.evidenceRepo = evidenceRepo;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public KolThemeSignalEntity applyStructuredResult(Long signalId, KolStructuredResultRequest request) {
        KolThemeSignalEntity signal = signalRepo.findById(signalId)
                .orElseThrow(() -> new IllegalArgumentException("signal not found: " + signalId));
        List<KolStructuredResultRequest.ThemeItem> themes =
                request.themes() == null ? List.of() : request.themes();
        long oldMappingCount = mappingRepo.countBySignalId(signalId);
        long oldEvidenceCount = evidenceRepo.countBySignalId(signalId);
        mappingRepo.deleteBySignalId(signalId);
        evidenceRepo.deleteBySignalId(signalId);
        boolean replacedExisting = oldMappingCount > 0 || oldEvidenceCount > 0;

        int mappingCount = 0;
        int evidenceCount = 0;
        for (KolStructuredResultRequest.ThemeItem theme : themes) {
            if (theme.themeTag() == null || theme.themeTag().isBlank()) continue;
            String direction = KolThemeSignalEngine.normalizeDirection(theme.direction());
            List<KolStructuredResultRequest.StockItem> stocks = theme.stocks() == null ? List.of() : theme.stocks();
            for (KolStructuredResultRequest.StockItem stock : stocks) {
                if (stock.symbol() == null || stock.symbol().isBlank()) continue;
                KolThemeStockMappingEntity mapping = new KolThemeStockMappingEntity();
                mapping.setSignalId(signalId);
                mapping.setTradingDate(signal.getTradingDate());
                mapping.setThemeTag(theme.themeTag().trim());
                mapping.setDirection(direction);
                mapping.setSymbol(stock.symbol().trim());
                mapping.setStockName(stock.stockName());
                mapping.setConfidence(stock.confidence());
                mapping.setPayloadJson(toJson(stock.payload()));
                mappingRepo.save(mapping);
                mappingCount++;
            }

            List<KolStructuredResultRequest.EvidenceItem> evidenceItems =
                    theme.evidence() == null ? List.of() : theme.evidence();
            for (KolStructuredResultRequest.EvidenceItem item : evidenceItems) {
                ThemeSignalEvidenceEntity evidence = new ThemeSignalEvidenceEntity();
                evidence.setSignalId(signalId);
                evidence.setTradingDate(signal.getTradingDate());
                evidence.setThemeTag(theme.themeTag().trim());
                evidence.setDirection(KolThemeSignalEngine.normalizeDirection(
                        item.direction() != null ? item.direction() : direction));
                evidence.setEvidenceType(normalizeEvidenceType(item.evidenceType()));
                evidence.setEvidenceText(item.evidenceText());
                evidence.setConfidence(item.confidence() != null ? item.confidence() : defaultConfidence(theme.confidence()));
                evidence.setEvidenceScore(null);
                evidence.setPayloadJson(toJson(item.payload()));
                evidenceRepo.save(evidence);
                evidenceCount++;
            }
        }

        signal.setSignalStatus("STRUCTURED");
        signal.setPayloadJson(mergeStructuredPayload(signal.getPayloadJson(), request.payload()));
        KolThemeSignalEntity saved = signalRepo.save(signal);
        traceService.write(signalId, "EXTRACTION", "STRUCTURED_RESULT_REPLACED", Map.of(
                "themeCount", themes.size(),
                "mappingCount", mappingCount,
                "evidenceCount", evidenceCount,
                "replacedExisting", replacedExisting,
                "oldMappingCount", oldMappingCount,
                "oldEvidenceCount", oldEvidenceCount,
                "weakSignalOnly", true
        ));
        return saved;
    }

    private BigDecimal defaultConfidence(BigDecimal themeConfidence) {
        return themeConfidence != null ? themeConfidence : BigDecimal.ONE;
    }

    private String normalizeEvidenceType(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String mergeStructuredPayload(String existingJson, Map<String, Object> structuredPayload) {
        try {
            Map<String, Object> map = existingJson == null || existingJson.isBlank()
                    ? new java.util.LinkedHashMap<>()
                    : objectMapper.convertValue(objectMapper.readTree(existingJson), java.util.LinkedHashMap.class);
            map.put("structuredPayload", structuredPayload == null ? Map.of() : structuredPayload);
            map.put("weakSignalOnly", true);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return toJson(Map.of("structuredPayload", structuredPayload == null ? Map.of() : structuredPayload,
                    "weakSignalOnly", true));
        }
    }

    private String toJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
