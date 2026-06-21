package com.austin.trading.service;

import com.austin.trading.dto.request.PromotionReviewDecisionRequest;
import com.austin.trading.dto.response.PromotionPolicySimulationResponse;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.dto.response.PromotionValidationReportResponse;
import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PromotionReviewService {
    public static final Set<String> ALLOWED_DECISION_STATUSES = Set.of(
            "WATCH_ONLY", "CANDIDATE_POOL_SHADOW", "NEED_MORE_EVIDENCE",
            "REJECTED", "BLOCKED_BY_RISK", "BLOCKED_BY_GOVERNANCE");
    public static final Set<String> FORBIDDEN_DECISION_STATUSES = Set.of(
            "TRADABLE", "BUY", "ENTER", "PROMOTED_TO_TRADABLE");

    private final PromotionReviewItemRepository itemRepo;
    private final PromotionReviewAuditRepository auditRepo;
    private final ResearchUniverseItemRepository researchRepo;
    private final HotGroupStockSignalRepository hotGroupRepo;
    private final ThemeReplayNodeRepository replayNodeRepo;
    private final ThemeLifecycleStateRepository lifecycleRepo;
    private final ThemeReplayMetricsRepository metricsRepo;
    private final CandidateStockRepository candidateStockRepo;
    private final FinalDecisionRepository finalDecisionRepo;
    private final CandidateForwardTrackingRepository forwardTrackingRepo;
    private final MarketIndexDailyRepository marketIndexRepo;
    private final ObjectMapper objectMapper;

    public PromotionReviewService(PromotionReviewItemRepository itemRepo,
                                  PromotionReviewAuditRepository auditRepo,
                                  ResearchUniverseItemRepository researchRepo,
                                  HotGroupStockSignalRepository hotGroupRepo,
                                  ThemeReplayNodeRepository replayNodeRepo,
                                  ThemeLifecycleStateRepository lifecycleRepo,
                                  ThemeReplayMetricsRepository metricsRepo,
                                  CandidateStockRepository candidateStockRepo,
                                  FinalDecisionRepository finalDecisionRepo,
                                  CandidateForwardTrackingRepository forwardTrackingRepo,
                                  MarketIndexDailyRepository marketIndexRepo,
                                  ObjectMapper objectMapper) {
        this.itemRepo = itemRepo;
        this.auditRepo = auditRepo;
        this.researchRepo = researchRepo;
        this.hotGroupRepo = hotGroupRepo;
        this.replayNodeRepo = replayNodeRepo;
        this.lifecycleRepo = lifecycleRepo;
        this.metricsRepo = metricsRepo;
        this.candidateStockRepo = candidateStockRepo;
        this.finalDecisionRepo = finalDecisionRepo;
        this.forwardTrackingRepo = forwardTrackingRepo;
        this.marketIndexRepo = marketIndexRepo;
        this.objectMapper = objectMapper;
    }

    public PromotionReviewResponse.SafetyBoundary safetyBoundary() {
        return PromotionReviewResponse.defaultSafetyBoundary();
    }

    @Transactional
    public PromotionReviewResponse rebuild(LocalDate date) {
        int preservedManualCount = safeInt(itemRepo.countManualItemsByDate(date));
        int preservedManualAuditCount = safeInt(auditRepo.countManualAuditsByDate(date));
        int deletedSystemAuditCount = auditRepo.deleteSystemBuildAuditsByDate(date);
        auditRepo.flush();
        int deletedSystemCount = itemRepo.deleteSystemGeneratedByDate(date);
        itemRepo.flush();
        BuildContext context = new BuildContext(preservedManualCount, preservedManualAuditCount, deletedSystemCount, deletedSystemAuditCount);
        return buildInternal(date, context);
    }

    @Transactional
    public PromotionReviewResponse build(LocalDate date) {
        return buildInternal(date, new BuildContext(0, 0, 0, 0));
    }

    private PromotionReviewResponse buildInternal(LocalDate date, BuildContext context) {
        Map<String, PromotionReviewItemEntity> existing = itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(date)
                .stream().collect(Collectors.toMap(this::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, ThemeLifecycleStateEntity> lifecycleByTheme = lifecycleRepo.findByTradingDateOrderByThemeTagAsc(date)
                .stream().collect(Collectors.toMap(ThemeLifecycleStateEntity::getThemeTag, Function.identity(), (a, b) -> a));
        Map<String, ThemeReplayMetricsEntity> metricsByTheme = metricsRepo.findByTradingDateOrderByThemeTagAsc(date)
                .stream().collect(Collectors.toMap(ThemeReplayMetricsEntity::getThemeTag, Function.identity(), (a, b) -> a));

        List<PromotionReviewItemEntity> built = new ArrayList<>();
        for (ResearchUniverseItemEntity r : researchRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(date)) {
            built.add(upsert(existing, fromResearch(r, lifecycleByTheme, metricsByTheme), "CREATE", "system/build", context));
        }
        for (HotGroupStockSignalEntity h : hotGroupRepo.findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(date, "POSTMARKET")) {
            built.add(upsert(existing, fromHotGroup(h, "HOT_GROUP_RADAR", lifecycleByTheme, metricsByTheme), "CREATE", "system/build", context));
            if (isLeader(h.getRole())) {
                built.add(upsert(existing, fromHotGroup(h, "RETAINED_LEADER", lifecycleByTheme, metricsByTheme), "CREATE", "system/build", context));
            } else {
                built.add(upsert(existing, fromHotGroup(h, "PEER_SHADOW", lifecycleByTheme, metricsByTheme), "CREATE", "system/build", context));
            }
            if (hasText(h.getCandidateAction()) || hasText(h.getRejectionReason()) || Boolean.TRUE.equals(h.getLimitRisk())) {
                built.add(upsert(existing, fromHotGroup(h, "EXPLAIN_MISS", lifecycleByTheme, metricsByTheme), "CREATE", "system/build", context));
            }
        }
        for (ThemeReplayNodeEntity n : replayNodeRepo.findByTradingDateOrderByThemeTagAscSymbolAsc(date)) {
            built.add(upsert(existing, fromReplayNode(n, lifecycleByTheme, metricsByTheme), "CREATE", "system/build", context));
        }
        return PromotionReviewResponse.of(date, built.stream().map(this::toItem).toList(),
                context.preservedManualCount(), context.mergedManualCount(), context.deletedSystemCount());
    }

    @Transactional(readOnly = true)
    public PromotionReviewResponse queue(LocalDate date) {
        return PromotionReviewResponse.of(date, itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(date)
                .stream().map(this::toItem).toList());
    }

    @Transactional(readOnly = true)
    public PromotionReviewResponse.Item item(Long id) {
        return itemRepo.findById(id).map(this::toItem)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion review item not found"));
    }

    @Transactional
    public PromotionReviewResponse.Item decide(Long id, PromotionReviewDecisionRequest request) {
        String status = request == null ? null : norm(request.status());
        if (FORBIDDEN_DECISION_STATUSES.contains(status) || !ALLOWED_DECISION_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid promotion review status. Promotion review cannot set TRADABLE/BUY/ENTER/PROMOTED_TO_TRADABLE.");
        }
        PromotionReviewItemEntity item = itemRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion review item not found"));
        String from = item.getCurrentStatus();
        item.setPreviousStatus(from);
        item.setCurrentStatus(status);
        item.setReviewer(blankToDefault(request.reviewer(), "system/manual"));
        item.setReviewedAt(LocalDateTime.now());
        item.setDecisionReason(request.reason());
        if ("BLOCKED_BY_RISK".equals(status)) item.setRiskBlocker(true);
        if ("BLOCKED_BY_GOVERNANCE".equals(status)) item.setGovernanceBlocker(true);
        PromotionReviewItemEntity saved = itemRepo.save(item);
        writeAudit(saved, from, status, actionFor(status), item.getReviewer(), request.reason(), saved.getPayloadJson());
        return toItem(saved);
    }

    @Transactional(readOnly = true)
    public PromotionReviewResponse.AuditResponse audit(LocalDate date, String symbol) {
        List<PromotionReviewAuditEntity> audits = hasText(symbol)
                ? auditRepo.findByTradingDateAndSymbolOrderByCreatedAtAscIdAsc(date, symbol)
                : auditRepo.findByTradingDateOrderByCreatedAtAscIdAsc(date);
        return PromotionReviewResponse.AuditResponse.of(date, symbol, audits.stream().map(this::toAuditItem).toList());
    }

    @Transactional(readOnly = true)
    public PromotionPolicySimulationResponse policySimulation(LocalDate startDate, LocalDate endDate, String status) {
        String effectiveStatus = blankToDefault(status, "CANDIDATE_POOL_SHADOW").trim().toUpperCase(Locale.ROOT);
        List<PromotionReviewItemEntity> reviewItems = itemRepo
                .findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(startDate, endDate, effectiveStatus);
        Map<String, CandidateForwardTrackingEntity> forwards = forwardTrackingRepo.findByTradingDateBetween(startDate, endDate).stream()
                .collect(Collectors.toMap(f -> forwardKey(f.getTradingDate(), f.getStockId()), Function.identity(), (a, b) -> a));

        List<PromotionPolicySimulationResponse.Item> rows = reviewItems.stream()
                .map(item -> toPolicySimulationItem(item, forwards.get(forwardKey(item.getTradingDate(), item.getSymbol()))))
                .toList();

        int matchedForwardCount = (int) rows.stream().filter(r -> r.dataGapReason() == null).count();
        int dataGapCount = rows.size() - matchedForwardCount;
        PromotionPolicySimulationResponse.Summary summary = new PromotionPolicySimulationResponse.Summary(
                rows.size(),
                matchedForwardCount,
                dataGapCount,
                avg(rows.stream().map(PromotionPolicySimulationResponse.Item::t1ReturnPct).toArray(BigDecimal[]::new)),
                avg(rows.stream().map(PromotionPolicySimulationResponse.Item::t5ReturnPct).toArray(BigDecimal[]::new)),
                avg(rows.stream().map(PromotionPolicySimulationResponse.Item::t10ReturnPct).toArray(BigDecimal[]::new)),
                winRate(rows),
                (int) rows.stream().filter(r -> Boolean.TRUE.equals(r.hitStop())).count(),
                avg(rows.stream().map(PromotionPolicySimulationResponse.Item::maxDrawdownPct).toArray(BigDecimal[]::new)),
                (int) rows.stream().filter(PromotionPolicySimulationResponse.Item::riskBlocker).count(),
                (int) rows.stream().filter(PromotionPolicySimulationResponse.Item::governanceBlocker).count());
        return PromotionPolicySimulationResponse.of(startDate, endDate, effectiveStatus, summary, rows);
    }

    @Transactional(readOnly = true)
    public PromotionValidationReportResponse validationReport(LocalDate startDate, LocalDate endDate, String status) {
        PromotionPolicySimulationResponse simulation = policySimulation(startDate, endDate, status);
        List<PromotionValidationReportResponse.Item> items = simulation.items().stream()
                .map(this::toValidationItem)
                .toList();
        int itemCount = items.size();
        int dataGapCount = (int) items.stream().filter(i -> "BLOCKED_BY_DATA_GAP".equals(i.validationStatus())).count();
        int evidenceReadyCount = itemCount - dataGapCount;
        int riskBlockedCount = (int) items.stream().filter(i -> "BLOCKED_BY_RISK".equals(i.validationStatus())).count();
        int governanceBlockedCount = (int) items.stream().filter(i -> "BLOCKED_BY_GOVERNANCE".equals(i.validationStatus())).count();
        BigDecimal avgT5 = avg(items.stream().map(PromotionValidationReportResponse.Item::t5ReturnPct).toArray(BigDecimal[]::new));
        BigDecimal winRateT5 = validationWinRate(items);
        BigDecimal hitStopRate = ratio(items.stream().filter(i -> Boolean.TRUE.equals(i.hitStop())).count(), evidenceReadyCount);
        BigDecimal avgMaxDrawdown = avg(items.stream().map(PromotionValidationReportResponse.Item::maxDrawdownPct).toArray(BigDecimal[]::new));
        PromotionValidationReportResponse.GraduationCriteria criteria = graduationCriteria();
        String overallStatus = overallValidationStatus(itemCount, evidenceReadyCount, dataGapCount, riskBlockedCount,
                governanceBlockedCount, avgT5, winRateT5, hitStopRate, avgMaxDrawdown, criteria);
        PromotionValidationReportResponse.Summary summary = new PromotionValidationReportResponse.Summary(
                itemCount, evidenceReadyCount, dataGapCount, riskBlockedCount, governanceBlockedCount,
                avgT5, winRateT5, hitStopRate, avgMaxDrawdown, overallStatus,
                overallValidationReason(overallStatus));
        return PromotionValidationReportResponse.of(startDate, endDate, simulation.status(), criteria, summary, items);
    }

    private PromotionValidationReportResponse.Item toValidationItem(PromotionPolicySimulationResponse.Item item) {
        String status = validationStatus(item);
        return new PromotionValidationReportResponse.Item(item.id(), item.tradingDate(), item.symbol(), item.stockName(),
                item.themeTag(), item.source(), item.currentStatus(), status, validationReason(status, item),
                item.t1ReturnPct(), item.t5ReturnPct(), item.t10ReturnPct(), item.maxDrawdownPct(), item.hitStop(), item.dataGapReason());
    }

    private String validationStatus(PromotionPolicySimulationResponse.Item item) {
        if (item.dataGapReason() != null) return "BLOCKED_BY_DATA_GAP";
        if (item.riskBlocker() || Boolean.TRUE.equals(item.hitStop())
                || (item.maxDrawdownPct() != null && item.maxDrawdownPct().compareTo(new BigDecimal("-8")) < 0)) {
            return "BLOCKED_BY_RISK";
        }
        if (item.governanceBlocker()) return "BLOCKED_BY_GOVERNANCE";
        if (item.t5ReturnPct() == null) return "NEED_MORE_EVIDENCE";
        if (item.t5ReturnPct().compareTo(BigDecimal.ZERO) > 0) return "ELIGIBLE_FOR_SOFT_BOOST_SHADOW";
        return "KEEP_WATCHING";
    }

    private String validationReason(String status, PromotionPolicySimulationResponse.Item item) {
        return switch (status) {
            case "BLOCKED_BY_DATA_GAP" -> item.dataGapReason();
            case "BLOCKED_BY_RISK" -> item.riskBlocker() ? "risk blocker" : Boolean.TRUE.equals(item.hitStop()) ? "hit stop" : "max drawdown below threshold";
            case "BLOCKED_BY_GOVERNANCE" -> "governance blocker";
            case "ELIGIBLE_FOR_SOFT_BOOST_SHADOW" -> "positive T5 shadow evidence; still shadow only";
            case "KEEP_WATCHING" -> "evidence available but threshold not met";
            default -> "insufficient forward evidence";
        };
    }

    private PromotionValidationReportResponse.GraduationCriteria graduationCriteria() {
        return new PromotionValidationReportResponse.GraduationCriteria(10, new BigDecimal("0.55"), BigDecimal.ZERO,
                new BigDecimal("0.25"), new BigDecimal("-8"));
    }

    private String overallValidationStatus(int itemCount, int evidenceReadyCount, int dataGapCount, int riskBlockedCount,
                                           int governanceBlockedCount, BigDecimal avgT5, BigDecimal winRateT5,
                                           BigDecimal hitStopRate, BigDecimal avgMaxDrawdown,
                                           PromotionValidationReportResponse.GraduationCriteria criteria) {
        if (itemCount == 0) return "NEED_MORE_EVIDENCE";
        if (evidenceReadyCount < criteria.minSample()) return dataGapCount > 0 ? "BLOCKED_BY_DATA_GAP" : "NEED_MORE_EVIDENCE";
        if (riskBlockedCount > 0) return "BLOCKED_BY_RISK";
        if (governanceBlockedCount > 0) return "BLOCKED_BY_GOVERNANCE";
        if (winRateT5 != null && winRateT5.compareTo(criteria.minWinRateT5()) >= 0
                && avgT5 != null && avgT5.compareTo(criteria.minAvgT5()) > 0
                && (hitStopRate == null || hitStopRate.compareTo(criteria.maxHitStopRate()) <= 0)
                && (avgMaxDrawdown == null || avgMaxDrawdown.compareTo(criteria.minAvgMaxDrawdown()) > 0)) {
            return "ELIGIBLE_FOR_SOFT_BOOST_SHADOW";
        }
        return "KEEP_WATCHING";
    }

    private String overallValidationReason(String status) {
        return switch (status) {
            case "BLOCKED_BY_DATA_GAP" -> "insufficient completed forward-return evidence or missing market data";
            case "BLOCKED_BY_RISK" -> "risk blocker, hit stop, or drawdown threshold triggered";
            case "BLOCKED_BY_GOVERNANCE" -> "governance blocker present";
            case "ELIGIBLE_FOR_SOFT_BOOST_SHADOW" -> "graduation criteria met for shadow-only soft boost review";
            case "KEEP_WATCHING" -> "sample exists but graduation thresholds are not met";
            default -> "minimum sample requirement not met";
        };
    }

    private BigDecimal validationWinRate(List<PromotionValidationReportResponse.Item> items) {
        long count = items.stream().filter(i -> i.t5ReturnPct() != null).count();
        if (count == 0) return null;
        long wins = items.stream().filter(i -> i.t5ReturnPct() != null && i.t5ReturnPct().compareTo(BigDecimal.ZERO) > 0).count();
        return ratio(wins, count);
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, java.math.RoundingMode.HALF_UP);
    }

    @Transactional
    public Map<String, Object> bridgeForwardTracking(LocalDate startDate, LocalDate endDate, String status) {
        String effectiveStatus = blankToDefault(status, "CANDIDATE_POOL_SHADOW").trim().toUpperCase(Locale.ROOT);
        String finalDecision = "PROMOTION_" + effectiveStatus;
        List<PromotionReviewItemEntity> reviewItems = itemRepo
                .findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(startDate, endDate, effectiveStatus);
        int written = 0;
        int skippedExisting = 0;
        int updatedExisting = 0;
        for (PromotionReviewItemEntity item : reviewItems) {
            Optional<CandidateForwardTrackingEntity> existing = forwardTrackingRepo.findByTradingDateAndStockIdAndFinalDecision(
                    item.getTradingDate(), item.getSymbol(), finalDecision);
            BigDecimal entryPrice = entryPriceAtDecision(item.getSymbol(), item.getTradingDate());
            if (existing.isPresent()) {
                CandidateForwardTrackingEntity row = existing.get();
                if (row.getEntryPriceAtDecision() == null && entryPrice != null) {
                    row.setEntryPriceAtDecision(entryPrice);
                    forwardTrackingRepo.save(row);
                    updatedExisting++;
                } else {
                    skippedExisting++;
                }
                continue;
            }
            CandidateForwardTrackingEntity row = new CandidateForwardTrackingEntity();
            row.setTradingDate(item.getTradingDate());
            row.setStockId(item.getSymbol());
            row.setStockName(item.getStockName());
            row.setFinalDecision(finalDecision);
            row.setFinalScore(item.getEvidenceScore());
            row.setGrade(item.getLifecycleStage());
            row.setPrimaryStrategy("PROMOTION_REVIEW");
            row.setGateName(suggestedPolicy(item));
            row.setEntryPriceAtDecision(entryPrice);
            row.setThemeTag(item.getThemeTag());
            row.setThemeReason(firstText(item.getReviewReason(), item.getExplainMissReason(), item.getDecisionReason()));
            row.setSourceCandidateId(item.getId());
            forwardTrackingRepo.save(row);
            written++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trackingBridgeOnly", true);
        result.put("doesNotAffectFinalDecision", true);
        result.put("doesNotAffectBuySellEnter", true);
        result.put("doesNotWriteCandidateStock", true);
        result.put("doesNotWriteProductionScore", true);
        result.put("noAutoPromotion", true);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("status", effectiveStatus);
        result.put("finalDecision", finalDecision);
        result.put("sourceItems", reviewItems.size());
        result.put("written", written);
        result.put("updatedExisting", updatedExisting);
        result.put("skippedExisting", skippedExisting);
        result.put("returnBackfillRequired", true);
        return result;
    }

    private PromotionPolicySimulationResponse.Item toPolicySimulationItem(PromotionReviewItemEntity item,
                                                                         CandidateForwardTrackingEntity forward) {
        String gapReason = forwardGapReason(forward);
        return new PromotionPolicySimulationResponse.Item(item.getId(), item.getTradingDate(), item.getSymbol(), item.getStockName(),
                item.getThemeTag(), item.getSource(), item.getCurrentStatus(), suggestedPolicy(item),
                forward == null ? null : forward.getT1CloseReturnPct(),
                forward == null ? null : forward.getT5CloseReturnPct(),
                forward == null ? null : forward.getT10CloseReturnPct(),
                forward == null ? null : forward.getMaxDrawdownPct(),
                forward == null ? null : forward.getHitStop(),
                Boolean.TRUE.equals(item.getRiskBlocker()), Boolean.TRUE.equals(item.getGovernanceBlocker()), gapReason);
    }

    private BigDecimal winRate(List<PromotionPolicySimulationResponse.Item> rows) {
        long count = rows.stream().filter(r -> r.t5ReturnPct() != null).count();
        if (count == 0) return null;
        long wins = rows.stream().filter(r -> r.t5ReturnPct() != null && r.t5ReturnPct().compareTo(BigDecimal.ZERO) > 0).count();
        return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(count), 4, java.math.RoundingMode.HALF_UP);
    }

    private String forwardGapReason(CandidateForwardTrackingEntity forward) {
        if (forward == null) return "MISSING_FORWARD_TRACKING";
        if (forward.getEntryPriceAtDecision() == null) return "MISSING_ENTRY_PRICE_AT_DECISION";
        if (forward.getT1CloseReturnPct() == null && forward.getT5CloseReturnPct() == null && forward.getT10CloseReturnPct() == null) {
            return "PENDING_FORWARD_RETURN_BACKFILL";
        }
        return null;
    }

    private BigDecimal entryPriceAtDecision(String symbol, LocalDate tradingDate) {
        return marketIndexRepo.findBySymbolAndTradingDate(symbol, tradingDate)
                .map(MarketIndexDailyEntity::getClosePrice)
                .or(() -> marketIndexRepo.findLatestBySymbolBefore(symbol, tradingDate.plusDays(1), PageRequest.of(0, 1)).stream()
                        .findFirst()
                        .map(MarketIndexDailyEntity::getClosePrice))
                .orElse(null);
    }

    private String suggestedPolicy(PromotionReviewItemEntity item) {
        if (Boolean.TRUE.equals(item.getRiskBlocker())) return "BLOCKED_BY_RISK";
        if (Boolean.TRUE.equals(item.getGovernanceBlocker())) return "BLOCKED_BY_GOVERNANCE";
        if (item.getEvidenceScore() == null && item.getRadarScore() == null && item.getReplayMetricScore() == null && item.getThemeImportanceScore() == null) {
            return "NEED_MORE_EVIDENCE";
        }
        return "ELIGIBLE_FOR_SOFT_BOOST_SHADOW";
    }

    private String forwardKey(LocalDate date, String symbol) {
        return date + "|" + symbol;
    }

    PromotionReviewResponse.Item toItem(PromotionReviewItemEntity e) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", e.getSource());
        evidence.put("researchRole", e.getResearchRole());
        evidence.put("riskBlocker", Boolean.TRUE.equals(e.getRiskBlocker()));
        evidence.put("governanceBlocker", Boolean.TRUE.equals(e.getGovernanceBlocker()));
        evidence.put("lifecycleStage", e.getLifecycleStage());
        evidence.put("themeImportanceScore", e.getThemeImportanceScore());
        evidence.put("tradableScore", e.getTradableScore());
        evidence.put("radarScore", e.getRadarScore());
        evidence.put("replayMetricScore", e.getReplayMetricScore());
        evidence.put("explainMissReason", e.getExplainMissReason());
        evidence.put("safetyFlags", Map.of(
                "reviewOnly", true,
                "notFinalDecisionEligible", true,
                "tradable", false,
                "candidatePoolShadowIsNotTradable", true,
                "noAutoPromotion", true));
        return new PromotionReviewResponse.Item(e.getId(), e.getTradingDate(), e.getSymbol(), e.getStockName(), e.getThemeTag(),
                e.getSource(), e.getResearchRole(), e.getCurrentStatus(), e.getPreviousStatus(), e.getReviewReason(), e.getEvidenceScore(),
                Boolean.TRUE.equals(e.getRiskBlocker()), Boolean.TRUE.equals(e.getGovernanceBlocker()), e.getLifecycleStage(),
                e.getThemeImportanceScore(), e.getTradableScore(), e.getRadarScore(), e.getReplayMetricScore(), e.getExplainMissReason(),
                e.getReviewer(), e.getReviewedAt(), e.getDecisionReason(), suggestedStatus(e), false, true, safetyBoundary(), evidence, e.getPayloadJson());
    }

    private PromotionReviewResponse.AuditItem toAuditItem(PromotionReviewAuditEntity e) {
        return new PromotionReviewResponse.AuditItem(e.getId(), e.getReviewItemId(), e.getTradingDate(), e.getSymbol(),
                e.getFromStatus(), e.getToStatus(), e.getAction(), e.getActor(), e.getReason(), e.getPayloadJson(), e.getCreatedAt(), safetyBoundary());
    }

    private PromotionReviewItemEntity upsert(Map<String, PromotionReviewItemEntity> existing,
                                             PromotionReviewItemEntity next,
                                             String action,
                                             String actor,
                                             BuildContext context) {
        PromotionReviewItemEntity target = existing.get(key(next));
        boolean mergeManual = target != null && isManualPreserved(target);
        if (target == null) {
            target = next;
        } else {
            String manualPayload = target.getPayloadJson();
            copyMutable(next, target);
            if (mergeManual) {
                target.setPayloadJson(mergeManualEvidencePayload(manualPayload, next.getPayloadJson()));
                context.incrementMergedManualCount();
            }
        }
        PromotionReviewItemEntity saved = itemRepo.save(target);
        existing.put(key(saved), saved);
        writeAudit(saved, null, saved.getCurrentStatus(), mergeManual ? "MERGE_EVIDENCE" : action, actor,
                mergeManual ? "Merged system evidence into preserved manual promotion review decision." : saved.getReviewReason(),
                saved.getPayloadJson());
        return saved;
    }


    private boolean isManualPreserved(PromotionReviewItemEntity item) {
        return hasText(item.getReviewer())
                || item.getReviewedAt() != null
                || hasText(item.getDecisionReason())
                || ALLOWED_DECISION_STATUSES.contains(norm(item.getCurrentStatus()));
    }

    private String mergeManualEvidencePayload(String manualPayload, String systemPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manualPreserved", true);
        payload.put("manualEvidencePayload", manualPayload);
        payload.put("mergedEvidencePayload", systemPayload);
        payload.put("safetyBoundary", Map.of(
                "reviewOnly", true,
                "doesNotAffectFinalDecision", true,
                "doesNotAffectBuySellEnter", true,
                "doesNotWriteCandidateStock", true,
                "doesNotWriteProductionScore", true,
                "candidatePoolShadowIsNotTradable", true,
                "noAutoPromotion", true,
                "promotionRequiresSeparateRiskGate", true));
        return toJson(payload);
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static final class BuildContext {
        private final int preservedManualCount;
        private final int preservedManualAuditCount;
        private final int deletedSystemCount;
        private final int deletedSystemAuditCount;
        private int mergedManualCount;

        private BuildContext(int preservedManualCount, int preservedManualAuditCount, int deletedSystemCount, int deletedSystemAuditCount) {
            this.preservedManualCount = preservedManualCount;
            this.preservedManualAuditCount = preservedManualAuditCount;
            this.deletedSystemCount = deletedSystemCount;
            this.deletedSystemAuditCount = deletedSystemAuditCount;
        }

        private int preservedManualCount() { return preservedManualCount; }
        private int preservedManualAuditCount() { return preservedManualAuditCount; }
        private int deletedSystemCount() { return deletedSystemCount; }
        private int deletedSystemAuditCount() { return deletedSystemAuditCount; }
        private int mergedManualCount() { return mergedManualCount; }
        private void incrementMergedManualCount() { mergedManualCount++; }
    }

    private void copyMutable(PromotionReviewItemEntity from, PromotionReviewItemEntity to) {
        to.setStockName(from.getStockName());
        to.setResearchRole(from.getResearchRole());
        to.setReviewReason(from.getReviewReason());
        to.setEvidenceScore(from.getEvidenceScore());
        to.setRiskBlocker(from.getRiskBlocker());
        to.setGovernanceBlocker(from.getGovernanceBlocker());
        to.setLifecycleStage(from.getLifecycleStage());
        to.setThemeImportanceScore(from.getThemeImportanceScore());
        to.setTradableScore(from.getTradableScore());
        to.setRadarScore(from.getRadarScore());
        to.setReplayMetricScore(from.getReplayMetricScore());
        to.setExplainMissReason(from.getExplainMissReason());
        to.setPayloadJson(from.getPayloadJson());
    }

    private PromotionReviewItemEntity fromResearch(ResearchUniverseItemEntity r,
                                                   Map<String, ThemeLifecycleStateEntity> lifecycleByTheme,
                                                   Map<String, ThemeReplayMetricsEntity> metricsByTheme) {
        PromotionReviewItemEntity e = base(r.getTradingDate(), r.getSymbol(), r.getStockName(), r.getThemeTag(), safeSource(r.getSource(), "RESEARCH_UNIVERSE"));
        e.setResearchRole(r.getResearchRole());
        e.setCurrentStatus("RESEARCH_ONLY");
        e.setReviewReason("Research Universe item; review-only, not FinalDecision eligible.");
        e.setThemeImportanceScore(r.getThemeImportanceScore());
        e.setTradableScore(r.getTradableScore());
        e.setRiskBlocker(Boolean.TRUE.equals(r.getLeadershipOnly()) && !Boolean.TRUE.equals(r.getLeaderTradable()));
        e.setGovernanceBlocker(Boolean.TRUE.equals(r.getPromotedToTradable()) || Boolean.TRUE.equals(r.getTradableUniverse()));
        e.setExplainMissReason(r.getBlockedReason());
        enrichLifecycleAndMetrics(e, lifecycleByTheme, metricsByTheme);
        finishEvidence(e, r.getPayloadJson());
        return e;
    }

    private PromotionReviewItemEntity fromHotGroup(HotGroupStockSignalEntity h,
                                                   String source,
                                                   Map<String, ThemeLifecycleStateEntity> lifecycleByTheme,
                                                   Map<String, ThemeReplayMetricsEntity> metricsByTheme) {
        PromotionReviewItemEntity e = base(h.getTradingDate(), h.getSymbol(), h.getStockName(), h.getThemeTag(), source);
        e.setResearchRole(isLeader(h.getRole()) ? "LEADERSHIP_ONLY" : h.getRole());
        e.setCurrentStatus("PENDING_REVIEW");
        e.setReviewReason("Hot Group Radar / explain-miss evidence; review-only and not tradable.");
        e.setRadarScore(h.getRadarRankScore());
        e.setRiskBlocker(Boolean.TRUE.equals(h.getLimitRisk()));
        e.setTradableScore(BigDecimal.ZERO);
        e.setExplainMissReason(firstText(h.getRejectionReason(), h.getCandidateAction(), "radar_watch_only"));
        enrichLifecycleAndMetrics(e, lifecycleByTheme, metricsByTheme);
        finishEvidence(e, h.getEvidenceJson());
        return e;
    }

    private PromotionReviewItemEntity fromReplayNode(ThemeReplayNodeEntity n,
                                                     Map<String, ThemeLifecycleStateEntity> lifecycleByTheme,
                                                     Map<String, ThemeReplayMetricsEntity> metricsByTheme) {
        String source = Boolean.TRUE.equals(n.getLeadershipOnly()) || Boolean.TRUE.equals(n.getIsThemeLeader()) ? "RETAINED_LEADER" : "PEER_SHADOW";
        PromotionReviewItemEntity e = base(n.getTradingDate(), n.getSymbol(), n.getStockName(), n.getThemeTag(), source);
        e.setResearchRole(n.getResearchRole());
        e.setCurrentStatus("RESEARCH_ONLY");
        e.setReviewReason("Theme replay node; replay/research-only evidence.");
        e.setThemeImportanceScore(n.getThemeImportanceScore());
        e.setTradableScore(n.getTradableScore());
        e.setRiskBlocker(Boolean.TRUE.equals(n.getRiskRejected()) || (Boolean.TRUE.equals(n.getLeadershipOnly()) && !Boolean.TRUE.equals(n.getLeaderTradable())));
        e.setGovernanceBlocker(Boolean.TRUE.equals(n.getTradableUniverse()));
        e.setExplainMissReason(n.getRejectionReason());
        enrichLifecycleAndMetrics(e, lifecycleByTheme, metricsByTheme);
        finishEvidence(e, n.getPayloadJson());
        return e;
    }

    private PromotionReviewItemEntity base(LocalDate date, String symbol, String name, String theme, String source) {
        PromotionReviewItemEntity e = new PromotionReviewItemEntity();
        e.setTradingDate(date);
        e.setSymbol(symbol);
        e.setStockName(name);
        e.setThemeTag(blankToDefault(theme, "UNKNOWN_THEME"));
        e.setSource(source);
        e.setRiskBlocker(false);
        e.setGovernanceBlocker(false);
        return e;
    }

    private void enrichLifecycleAndMetrics(PromotionReviewItemEntity e,
                                           Map<String, ThemeLifecycleStateEntity> lifecycleByTheme,
                                           Map<String, ThemeReplayMetricsEntity> metricsByTheme) {
        ThemeLifecycleStateEntity lifecycle = lifecycleByTheme.get(e.getThemeTag());
        if (lifecycle != null) {
            e.setLifecycleStage(lifecycle.getStage());
        }
        ThemeReplayMetricsEntity metrics = metricsByTheme.get(e.getThemeTag());
        if (metrics != null) {
            e.setReplayMetricScore(avg(metrics.getLeaderRetentionRate(), metrics.getPeerDiscoveryHitRate(), metrics.getResearchUniverseCoverage()));
        }
    }

    private void finishEvidence(PromotionReviewItemEntity e, String sourcePayload) {
        e.setEvidenceScore(avg(e.getThemeImportanceScore(), e.getTradableScore(), e.getRadarScore(), e.getReplayMetricScore()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourcePayload", sourcePayload);
        payload.put("suggestedStatus", suggestedStatus(e));
        payload.put("tradable", false);
        payload.put("notFinalDecisionEligible", true);
        payload.put("safetyBoundary", Map.of(
                "reviewOnly", true,
                "doesNotAffectFinalDecision", true,
                "doesNotAffectBuySellEnter", true,
                "doesNotWriteCandidateStock", true,
                "doesNotWriteProductionScore", true,
                "candidatePoolShadowIsNotTradable", true,
                "noAutoPromotion", true,
                "promotionRequiresSeparateRiskGate", true));
        e.setPayloadJson(toJson(payload));
    }

    private String suggestedStatus(PromotionReviewItemEntity e) {
        if (Boolean.TRUE.equals(e.getGovernanceBlocker())) return "BLOCKED_BY_GOVERNANCE";
        if (Boolean.TRUE.equals(e.getRiskBlocker())) return "BLOCKED_BY_RISK";
        if (e.getRadarScore() == null && e.getReplayMetricScore() == null && e.getThemeImportanceScore() == null) return "NEED_MORE_EVIDENCE";
        if (e.getRadarScore() != null && e.getRadarScore().compareTo(new BigDecimal("20")) >= 0 && !isLeader(e.getResearchRole())) return "CANDIDATE_POOL_SHADOW";
        return "WATCH_ONLY";
    }

    private void writeAudit(PromotionReviewItemEntity item, String from, String to, String action, String actor, String reason, String payload) {
        PromotionReviewAuditEntity audit = new PromotionReviewAuditEntity();
        audit.setReviewItemId(item.getId());
        audit.setTradingDate(item.getTradingDate());
        audit.setSymbol(item.getSymbol());
        audit.setFromStatus(from);
        audit.setToStatus(to);
        audit.setAction(action);
        audit.setActor(actor);
        audit.setReason(reason);
        audit.setPayloadJson(payload);
        auditRepo.save(audit);
    }

    private String actionFor(String status) {
        return switch (status) {
            case "CANDIDATE_POOL_SHADOW" -> "APPROVE_SHADOW";
            case "REJECTED" -> "REJECT";
            case "BLOCKED_BY_RISK", "BLOCKED_BY_GOVERNANCE" -> "BLOCK";
            case "NEED_MORE_EVIDENCE" -> "NEED_MORE_EVIDENCE";
            default -> "REVIEW";
        };
    }

    private BigDecimal avg(BigDecimal... vals) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal v : vals) {
            if (v != null) { sum = sum.add(v); count++; }
        }
        return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 4, java.math.RoundingMode.HALF_UP);
    }

    private boolean isLeader(String role) {
        if (!hasText(role)) return false;
        String r = role.toUpperCase(Locale.ROOT);
        return r.equals("THEME_LEADER") || r.equals("LEADERSHIP_ONLY") || r.equals("RETAINED_LEADER") || r.equals("LEADERSHIP");
    }

    private String safeSource(String source, String fallback) {
        String s = blankToDefault(source, fallback).toUpperCase(Locale.ROOT);
        if (Set.of("HOT_GROUP_RADAR", "RESEARCH_UNIVERSE", "PEER_SHADOW", "RETAINED_LEADER", "EXPLAIN_MISS", "LIFECYCLE", "MANUAL").contains(s)) return s;
        return fallback;
    }

    private String key(PromotionReviewItemEntity e) {
        return e.getTradingDate() + "|" + e.getSymbol() + "|" + blankToDefault(e.getThemeTag(), "UNKNOWN_THEME") + "|" + e.getSource();
    }

    private boolean hasText(String s) { return s != null && !s.isBlank(); }
    private String norm(String s) { return hasText(s) ? s.trim().toUpperCase(Locale.ROOT) : ""; }
    private String blankToDefault(String s, String fallback) { return hasText(s) ? s : fallback; }
    private String firstText(String... vals) { for (String v : vals) if (hasText(v)) return v; return null; }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
