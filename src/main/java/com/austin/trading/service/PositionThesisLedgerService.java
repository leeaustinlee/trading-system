package com.austin.trading.service;

import com.austin.trading.dto.response.PositionThesisLedgerResponse;
import com.austin.trading.dto.response.ThemeContextSnapshot;
import com.austin.trading.entity.DecisionSnapshotLedgerEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionThesisLedgerEntity;
import com.austin.trading.repository.DecisionSnapshotLedgerRepository;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.PositionThesisLedgerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PositionThesisLedgerService {
    private final PositionRepository positionRepository;
    private final PaperTradeRepository paperTradeRepository;
    private final PositionThesisLedgerRepository thesisRepository;
    private final FinalDecisionRepository finalDecisionRepository;
    private final DecisionSnapshotLedgerRepository snapshotRepository;
    private final PortfolioHealthV2Service healthV2Service;
    private final ThemeIntelligenceService themeIntelligenceService;
    private final ObjectMapper objectMapper;

    public PositionThesisLedgerService(PositionRepository positionRepository,
                                       PaperTradeRepository paperTradeRepository,
                                       PositionThesisLedgerRepository thesisRepository,
                                       FinalDecisionRepository finalDecisionRepository,
                                       DecisionSnapshotLedgerRepository snapshotRepository,
                                       PortfolioHealthV2Service healthV2Service,
                                       ObjectMapper objectMapper) {
        this(positionRepository, paperTradeRepository, thesisRepository, finalDecisionRepository,
                snapshotRepository, healthV2Service, null, objectMapper);
    }

    @Autowired
    public PositionThesisLedgerService(PositionRepository positionRepository,
                                       PaperTradeRepository paperTradeRepository,
                                       PositionThesisLedgerRepository thesisRepository,
                                       FinalDecisionRepository finalDecisionRepository,
                                       DecisionSnapshotLedgerRepository snapshotRepository,
                                       PortfolioHealthV2Service healthV2Service,
                                       ThemeIntelligenceService themeIntelligenceService,
                                       ObjectMapper objectMapper) {
        this.positionRepository = positionRepository;
        this.paperTradeRepository = paperTradeRepository;
        this.thesisRepository = thesisRepository;
        this.finalDecisionRepository = finalDecisionRepository;
        this.snapshotRepository = snapshotRepository;
        this.healthV2Service = healthV2Service;
        this.themeIntelligenceService = themeIntelligenceService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PositionThesisLedgerResponse refreshOpenTheses() {
        Map<String, Map<String, Object>> healthBySymbol = healthRowsBySymbol();
        List<PositionThesisLedgerResponse.Item> items = positionRepository.findByStatus("OPEN").stream()
                .map(p -> refreshOne(p, healthBySymbol.get(p.getSymbol())))
                .map(PositionThesisLedgerResponse.Item::from)
                .toList();
        return PositionThesisLedgerResponse.of(items);
    }

    @Transactional(readOnly = true)
    public PositionThesisLedgerResponse openTheses() {
        return PositionThesisLedgerResponse.of(thesisRepository.findByOpenPositionTrueOrderByLatestReviewDateDescIdDesc()
                .stream().map(PositionThesisLedgerResponse.Item::from).toList());
    }

    @Transactional(readOnly = true)
    public Optional<PositionThesisLedgerEntity> getOpenThesisBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return thesisRepository.findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc(symbol);
    }

    @Transactional(readOnly = true)
    public Optional<PositionThesisLedgerResponse.Item> getOpenThesisItemBySymbol(String symbol) {
        return getOpenThesisBySymbol(symbol).map(PositionThesisLedgerResponse.Item::from);
    }

    private PositionThesisLedgerEntity refreshOne(PositionEntity p, Map<String, Object> health) {
        PaperTradeEntity paper = latestOpenPaperTrade(p.getSymbol()).orElse(null);
        Map<String, Object> entryPayload = parseJson(paper == null ? p.getPayloadJson() : coalesce(paper.getEntryPayloadJson(), paper.getPayloadJson(), p.getPayloadJson()));
        Map<String, Object> finalDecisionPayload = loadFinalDecisionPayload(paper);
        Map<String, Object> snapshotEvidence = loadSnapshotEvidence(paper);

        PositionThesisLedgerEntity e = thesisRepository
                .findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc(p.getSymbol())
                .orElseGet(PositionThesisLedgerEntity::new);
        e.setSymbol(p.getSymbol());
        e.setStockName(p.getStockName());
        e.setPositionId(p.getId());
        e.setPaperTradeId(paper == null ? null : paper.getId());
        e.setEntryDate(entryDate(p, paper));
        e.setAvgCost(p.getAvgCost());
        e.setEntrySource(coalesce(paper == null ? null : paper.getSource(), str(entryPayload.get("entrySource")), "POSITION"));
        e.setEntryDecisionId(paper == null ? null : paper.getFinalDecisionId());
        e.setPrimaryTheme(coalesce(paper == null ? null : paper.getThemeTag(), text(entryPayload, "primaryTheme", "themeTag", "theme"), text(finalDecisionPayload, "primaryTheme", "themeTag")));
        e.setSecondaryThemes(jsonString(firstNonNull(entryPayload.get("secondaryThemes"), finalDecisionPayload.get("secondaryThemes"), snapshotEvidence.get("secondaryThemes"))));
        e.setThesisSummary(coalesce(text(entryPayload, "thesisSummary", "thesis", "aiSummary", "summary"), text(finalDecisionPayload, "thesisSummary", "summary"), text(snapshotEvidence, "summary"), fallbackThesisSummary(e.getPrimaryTheme(), paper)));
        ThemeContextSnapshot narrative = loadThemeContext(e.getPrimaryTheme());
        applyNarrative(e, narrative, health);
        e.setEntryReason(coalesce(text(entryPayload, "entryReason", "reason", "candidateReason"), text(finalDecisionPayload, "entryReason", "reason", "summary"), p.getNote()));
        e.setExpectedHoldingDays(coalesce(paper == null ? null : paper.getMaxHoldingDays(), intValue(entryPayload.get("expectedHoldingDays")), Integer.valueOf(5)));
        e.setInvalidationCondition(coalesce(text(entryPayload, "invalidationCondition", "invalidCondition"), buildInvalidationCondition(p)));
        e.setStopType(buildStopType(p));
        e.setTargetWave(coalesce(text(entryPayload, "targetWave", "expectedWave"), buildTargetWave(p)));
        StatusDecision decision = decideThesisStatus(p, health, e.getPrimaryTheme());
        e.setThesisStatus(decision.status());
        e.setThesisConfidence(decision.confidence());
        e.setLatestReviewDate(LocalDateTime.now());
        e.setLatestReviewReason(decision.reason());
        e.setOpenPosition(true);
        e.setEvidenceJson(jsonString(evidenceMap(health, entryPayload, finalDecisionPayload, snapshotEvidence, decision)));
        e.setProductionDecisionAllowed(false);
        e.setAutoBuyEnabled(false);
        e.setAutoSellEnabled(false);
        e.setManualConfirmRequired(true);
        return thesisRepository.save(e);
    }

    private ThemeContextSnapshot loadThemeContext(String primaryTheme) {
        if (themeIntelligenceService == null || primaryTheme == null || primaryTheme.isBlank()) {
            return ThemeContextSnapshot.unknown(primaryTheme);
        }
        try {
            return themeIntelligenceService.context(primaryTheme);
        } catch (Exception e) {
            return ThemeContextSnapshot.unknown(primaryTheme);
        }
    }

    private void applyNarrative(PositionThesisLedgerEntity e, ThemeContextSnapshot narrative, Map<String, Object> health) {
        if (narrative == null) narrative = ThemeContextSnapshot.unknown(e.getPrimaryTheme());
        e.setThemeLifecycle(narrative.themeLifecycle());
        e.setThemeHeat(narrative.themeHeat());
        e.setThemeBreadth(narrative.themeBreadth());
        e.setRotationStrength(narrative.rotationStrength());
        e.setNarrativeHeat(narrative.narrativeHeat());
        e.setCrowdingRisk(narrative.retailCrowding());
        e.setInstitutionalAlignment(narrative.institutionalAlignment());
        e.setWavePhase(resolveWavePhase(narrative, health));
        e.setMarketContext(jsonString(narrative.marketContext()));
        e.setSectorLeadership(narrative.sectorLeadership());
        e.setThemeStillActive(narrative.themeStillActive() || themeActive(health));
    }

    private String resolveWavePhase(ThemeContextSnapshot narrative, Map<String, Object> health) {
        String lifecycle = narrative == null ? "UNKNOWN" : String.valueOf(narrative.themeLifecycle());
        BigDecimal heat = narrative == null ? null : narrative.themeHeat();
        BigDecimal crowding = narrative == null ? null : narrative.crowdingScore();
        boolean broken = structureBroken(health);
        if (broken || contains(lifecycle, "DEAD") || contains(lifecycle, "FADING")) return "BREAKDOWN";
        if (contains(lifecycle, "EARLY") || contains(lifecycle, "EMERGING")) return "EARLY_BREAKOUT";
        if (contains(lifecycle, "ACTIVE") || contains(lifecycle, "MAINSTREAM")) return "MID_TREND_CONTINUATION";
        if (contains(lifecycle, "CROWDED") || (crowding != null && crowding.compareTo(new BigDecimal("0.75")) >= 0) || (heat != null && heat.compareTo(new BigDecimal("9.0")) >= 0)) return "LATE_EXTENSION";
        return "UNKNOWN";
    }

    private StatusDecision decideThesisStatus(PositionEntity p, Map<String, Object> health, String primaryTheme) {
        boolean hasHealth = health != null && !health.isEmpty();
        boolean hasTheme = primaryTheme != null && !primaryTheme.isBlank();
        if (!hasHealth && !hasTheme) {
            return decision("UNKNOWN", "資料不足：沒有 health-v2 與 entry thesis/theme evidence", "0.20");
        }
        boolean themeActive = themeActive(health);
        boolean structureIntact = structureIntact(health);
        boolean structureBroken = structureBroken(health);
        boolean rsStrong = contains(healthValue(health, "relativeStrengthStatus"), "OUTPERFORM") || contains(healthValue(health, "relativeStrengthStatus"), "STRONG");
        boolean rsWeak = contains(healthValue(health, "relativeStrengthStatus"), "UNDERPERFORM") || contains(healthValue(health, "relativeStrengthStatus"), "WEAK");
        boolean stopHit = stopHit(bd(healthValue(health, "currentPrice")), p.getStopLossPrice(), p.getTrailingStopPrice());
        BigDecimal pnlPct = pct(bd(healthValue(health, "currentPrice")), p.getAvgCost());
        boolean profitable = pnlPct != null && pnlPct.signum() > 0;
        boolean actionReduce = contains(healthValue(health, "actionTier"), "REDUCE") || contains(healthValue(health, "structureStatus"), "MA5_BREAK");

        if (stopHit && themeActive && structureIntact) {
            return decision("NEEDS_REVIEW", "價格觸及停損/移動停利，但題材與結構仍成立，僅列人工複核，不直接判 thesis 失效", "0.70");
        }
        if (!themeActive && structureBroken && rsWeak) {
            return decision("INVALIDATED", "題材退潮、結構破壞且相對強度轉弱，thesis 失效", "0.88");
        }
        if (!hasHealth || contains(healthValue(health, "structureStatus"), "DATA_GAP")) {
            return decision("UNKNOWN", "資料不足：health-v2 結構或題材 evidence 不完整", "0.25");
        }
        if (profitable && (actionReduce || rsWeak)) {
            return decision("WEAKENING", "持倉仍獲利但動能降溫/相對強度轉弱，建議人工檢視是否減碼", "0.65");
        }
        if (themeActive && structureIntact && rsStrong) {
            return decision("ACTIVE", "primaryTheme 仍 active、structure intact、relative strength 強，thesis 仍成立", "0.85");
        }
        return decision("NEEDS_REVIEW", "題材/結構 evidence 混合，需人工確認 thesis 是否仍成立", "0.50");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> healthRowsBySymbol() {
        try {
            Map<String, Object> summary = healthV2Service.healthV2ReadOnlySummary();
            Object positions = summary == null ? null : summary.get("positions");
            if (!(positions instanceof List<?> rows)) return Map.of();
            return rows.stream()
                    .filter(Map.class::isInstance)
                    .map(row -> (Map<String, Object>) row)
                    .filter(row -> row.get("symbol") != null)
                    .collect(Collectors.toMap(row -> String.valueOf(row.get("symbol")), Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Optional<PaperTradeEntity> latestOpenPaperTrade(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        List<PaperTradeEntity> rows = paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc(symbol, "OPEN");
        if (rows == null || rows.isEmpty()) return Optional.empty();
        return Optional.of(rows.get(rows.size() - 1));
    }

    private Map<String, Object> loadFinalDecisionPayload(PaperTradeEntity paper) {
        if (paper == null || paper.getFinalDecisionId() == null) return Map.of();
        try {
            Optional<FinalDecisionEntity> row = finalDecisionRepository.findById(paper.getFinalDecisionId());
            return row.map(r -> parseJson(r.getPayloadJson())).orElse(Map.of());
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> loadSnapshotEvidence(PaperTradeEntity paper) {
        if (paper == null || paper.getFinalDecisionId() == null) return Map.of();
        try {
            List<DecisionSnapshotLedgerEntity> rows = snapshotRepository.findByFinalDecisionIdOrderByCreatedAtDescIdDesc(paper.getFinalDecisionId());
            if (rows == null || rows.isEmpty()) return Map.of();
            DecisionSnapshotLedgerEntity s = rows.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("summary", s.getResponsePayloadJson());
            m.put("candidateScoresJson", s.getCandidateScoresJson());
            m.put("candidateUniverseJson", s.getCandidateUniverseJson());
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> evidenceMap(Map<String, Object> health, Map<String, Object> entryPayload,
                                            Map<String, Object> finalPayload, Map<String, Object> snapshot,
                                            StatusDecision decision) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "READ_ONLY_POSITION_THESIS_LEDGER");
        out.put("healthV2", health == null ? Map.of() : health);
        out.put("entryPayloadKeys", entryPayload == null ? List.of() : entryPayload.keySet());
        out.put("finalDecisionPayloadKeys", finalPayload == null ? List.of() : finalPayload.keySet());
        out.put("snapshotEvidenceKeys", snapshot == null ? List.of() : snapshot.keySet());
        out.put("statusReason", decision.reason());
        out.put("productionDecisionAllowed", false);
        out.put("autoBuyEnabled", false);
        out.put("autoSellEnabled", false);
        out.put("manualConfirmRequired", true);
        return out;
    }

    private LocalDate entryDate(PositionEntity p, PaperTradeEntity paper) {
        if (paper != null && paper.getEntryDate() != null) return paper.getEntryDate();
        if (p.getOpenedAt() != null) return p.getOpenedAt().toLocalDate();
        return null;
    }

    private String buildInvalidationCondition(PositionEntity p) {
        if (p.getStopLossPrice() != null) return "跌破停損價 " + p.getStopLossPrice() + " 且 health-v2 顯示題材/結構同步轉弱";
        return "題材退潮 + 結構破壞 + 相對強度轉弱";
    }

    private String buildStopType(PositionEntity p) {
        if (p.getTrailingStopPrice() != null) return "TRAILING_STOP_WITH_MANUAL_CONFIRM";
        if (p.getStopLossPrice() != null) return "STOP_LOSS_WITH_MANUAL_CONFIRM";
        return "MANUAL_REVIEW_ONLY";
    }

    private String buildTargetWave(PositionEntity p) {
        if (p.getTakeProfit2() != null) return "目標波段至 TP2 " + p.getTakeProfit2();
        if (p.getTakeProfit1() != null) return "第一段停利目標 " + p.getTakeProfit1();
        return null;
    }

    private String fallbackThesisSummary(String theme, PaperTradeEntity paper) {
        if (theme != null && !theme.isBlank()) return "持股 thesis 來自進場題材：「" + theme + "」";
        if (paper != null && paper.getStrategyType() != null) return "持股 thesis 來自進場策略：「" + paper.getStrategyType() + "」";
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String text(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty()) return null;
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    private Integer intValue(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? null : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    @SafeVarargs
    private final <T> T coalesce(T... values) {
        for (T v : values) {
            if (v instanceof String s) {
                if (!s.isBlank()) return v;
            } else if (v != null) {
                return v;
            }
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }

    private String jsonString(Object v) {
        if (v == null) return null;
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    private StatusDecision decision(String status, String reason, String confidence) {
        return new StatusDecision(status, reason, new BigDecimal(confidence));
    }

    private boolean themeActive(Map<String, Object> health) {
        Object inputs = healthValue(health, "healthInputs");
        if (inputs instanceof Map<?, ?> raw) {
            Object mainstream = raw.get("mainstreamTheme");
            Object stage = raw.get("themeStage");
            return Boolean.TRUE.equals(mainstream) || contains(stage, "ACTIVE") || contains(stage, "MAINSTREAM");
        }
        return false;
    }

    private boolean structureIntact(Map<String, Object> health) {
        Object status = healthValue(health, "structureStatus");
        return signalsContain(health, "structure_intact")
                || contains(status, "INTACT")
                || (!contains(status, "BREAK") && !contains(status, "BROKEN") && !contains(status, "DATA_GAP") && status != null);
    }

    private boolean structureBroken(Map<String, Object> health) {
        Object status = healthValue(health, "structureStatus");
        return signalsContain(health, "structure_broken")
                || contains(status, "PREVIOUS_LOW_BREAK")
                || contains(status, "MA10_BREAK")
                || contains(status, "MA20_BREAK")
                || contains(status, "BROKEN");
    }

    private boolean signalsContain(Map<String, Object> health, String needle) {
        Object signals = healthValue(health, "structuralSignals");
        if (signals instanceof List<?> list) return list.stream().anyMatch(v -> contains(v, needle));
        return contains(signals, needle);
    }

    private boolean stopHit(BigDecimal current, BigDecimal stopLoss, BigDecimal trailingStop) {
        if (current == null || current.signum() <= 0) return false;
        if (stopLoss != null && current.compareTo(stopLoss) <= 0) return true;
        return trailingStop != null && current.compareTo(trailingStop) <= 0;
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0) return null;
        return price.subtract(base).divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            if (v == null || "DATA_GAP".equalsIgnoreCase(String.valueOf(v))) return null;
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private Object healthValue(Map<String, Object> health, String key) {
        return health == null ? null : health.get(key);
    }

    private boolean contains(Object value, String needle) {
        if (value == null || needle == null) return false;
        return String.valueOf(value).toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record StatusDecision(String status, String reason, BigDecimal confidence) {}
}
