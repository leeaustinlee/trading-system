package com.austin.trading.service;

import com.austin.trading.dto.response.LifecyclePullbackPlanShadowResponse;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.ThemeAdmissionShadowDecisionEntity;
import com.austin.trading.entity.ThemeLifecycleStateEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.ThemeAdmissionShadowDecisionRepository;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** P3-E read-only pullback plan shadow report. */
@Service
public class LifecyclePullbackPlanShadowService {
    private static final int MAX_REPORT_DAYS = 365;
    private static final BigDecimal HIGH_LIMIT_UP_DENSITY = new BigDecimal("0.1500");
    private static final BigDecimal HIGH_CROWDING = new BigDecimal("0.7000");

    private final ThemeAdmissionShadowDecisionRepository admissionRepository;
    private final ThemeLifecycleStateRepository lifecycleRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;

    public LifecyclePullbackPlanShadowService(ThemeAdmissionShadowDecisionRepository admissionRepository,
                                              ThemeLifecycleStateRepository lifecycleRepository,
                                              CandidateForwardTrackingRepository forwardTrackingRepository) {
        this.admissionRepository = admissionRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
    }

    @Transactional(readOnly = true)
    public LifecyclePullbackPlanShadowResponse report(int days) {
        Window window = window(days);
        List<ThemeAdmissionShadowDecisionEntity> admissions = admissionRepository
                .findByTradingDateBetweenOrderByTradingDateDescThemeTagAscSymbolAsc(window.start(), window.end());
        Map<LifecycleKey, ThemeLifecycleStateEntity> lifecycle = lifecycleRepository.findAll().stream()
                .filter(e -> e.getTradingDate() != null && !e.getTradingDate().isBefore(window.start()) && !e.getTradingDate().isAfter(window.end()))
                .collect(Collectors.toMap(e -> new LifecycleKey(e.getTradingDate(), norm(e.getThemeTag())), Function.identity(), (a, b) -> a));
        Map<ForwardKey, CandidateForwardTrackingEntity> forwards = forwardTrackingRepository.findByTradingDateBetween(window.start(), window.end()).stream()
                .collect(Collectors.toMap(e -> new ForwardKey(e.getTradingDate(), norm(e.getStockId())), Function.identity(), (a, b) -> a));

        List<LifecyclePullbackPlanShadowResponse.Item> rows = admissions.stream()
                .filter(this::isPullbackRelevant)
                .map(e -> toItem(e,
                        lifecycle.get(new LifecycleKey(e.getTradingDate(), norm(e.getThemeTag()))),
                        forwards.get(new ForwardKey(e.getTradingDate(), norm(e.getSymbol())))))
                .sorted(Comparator.comparing(LifecyclePullbackPlanShadowResponse.Item::tradingDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(LifecyclePullbackPlanShadowResponse.Item::themeTag, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LifecyclePullbackPlanShadowResponse.Item::symbol, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<String> gaps = dataGaps(admissions, rows, lifecycle, forwards);
        return LifecyclePullbackPlanShadowResponse.of(
                window.requestedDays(), window.start(), window.end(), admissions.size(),
                rows.stream().filter(r -> Boolean.TRUE.equals(r.wouldCreatePullbackPlan())).count(),
                rows.stream().filter(r -> "AVOID_CHASING".equals(r.planStatus())).count(),
                rows.stream().filter(r -> "WATCH_PULLBACK".equals(r.planStatus())).count(),
                rows.stream().filter(r -> "WAIT_SUPPORT".equals(r.planStatus())).count(),
                average(rows, LifecyclePullbackPlanShadowResponse.Item::actualReturn5d),
                average(rows, LifecyclePullbackPlanShadowResponse.Item::actualReturn10d),
                average(rows, LifecyclePullbackPlanShadowResponse.Item::maxDrawdownPct),
                byStatus(rows), byStage(rows), rows, gaps);
    }

    static String planStatus(ThemeAdmissionShadowDecisionEntity admission, ThemeLifecycleStateEntity lifecycle) {
        if (admission == null) {
            return "NO_PLAN";
        }
        String stage = lifecycle == null ? null : upper(lifecycle.getStage());
        boolean pullback = Boolean.TRUE.equals(admission.getWouldCreatePullbackPlan());
        boolean nearLimit = Boolean.TRUE.equals(admission.getNearLimit());
        boolean highLimitDensity = lifecycle != null && gte(lifecycle.getLimitUpDensity(), HIGH_LIMIT_UP_DENSITY);
        boolean crowded = lifecycle != null && gte(lifecycle.getCrowdingScore(), HIGH_CROWDING);
        if (!pullback && !nearLimit && !highLimitDensity) {
            return "NO_PLAN";
        }
        if ("OVERHEATED".equals(stage) || "DISTRIBUTION".equals(stage) || crowded) {
            return "AVOID_CHASING";
        }
        if (nearLimit || highLimitDensity || pullback) {
            return "WATCH_PULLBACK";
        }
        return "WAIT_SUPPORT";
    }

    static String planReason(ThemeAdmissionShadowDecisionEntity admission, ThemeLifecycleStateEntity lifecycle, String status) {
        String stage = lifecycle == null ? "UNKNOWN" : value(lifecycle.getStage(), "UNKNOWN");
        String limitRisk = admission == null ? "UNKNOWN" : value(admission.getLimitRisk(), "UNKNOWN");
        return "SHADOW_ONLY:" + status
                + "; lifecycleStage=" + stage
                + "; nearLimit=" + (admission != null && Boolean.TRUE.equals(admission.getNearLimit()))
                + "; wouldCreatePullbackPlan=" + (admission != null && Boolean.TRUE.equals(admission.getWouldCreatePullbackPlan()))
                + "; limitRisk=" + limitRisk
                + "; doesNotAffectBuySell=true; doesNotWriteCandidateWatchlist=true";
    }

    private LifecyclePullbackPlanShadowResponse.Item toItem(ThemeAdmissionShadowDecisionEntity admission,
                                                            ThemeLifecycleStateEntity lifecycle,
                                                            CandidateForwardTrackingEntity forward) {
        String status = planStatus(admission, lifecycle);
        return new LifecyclePullbackPlanShadowResponse.Item(
                admission.getTradingDate(), admission.getSymbol(), admission.getStockName(), admission.getThemeTag(),
                lifecycle == null ? "UNKNOWN" : lifecycle.getStage(),
                lifecycle == null ? null : lifecycle.getLifecycleScore(),
                lifecycle == null ? null : lifecycle.getContinuationDays(),
                lifecycle == null ? null : lifecycle.getBreadth(),
                lifecycle == null ? null : lifecycle.getLeaderCount(),
                lifecycle == null ? null : lifecycle.getCrowdingScore(),
                lifecycle == null ? null : lifecycle.getLimitUpDensity(),
                admission.getNearLimit(), admission.getLimitRisk(), admission.getWouldCreatePullbackPlan(),
                admission.getWouldWriteCandidate(), admission.getWouldWriteWatchlist(),
                admission.getShadowAction() == null ? null : admission.getShadowAction().name(),
                status, planReason(admission, lifecycle, status),
                forward == null ? null : forward.getT5CloseReturnPct(),
                forward == null ? null : forward.getT10CloseReturnPct(),
                forward == null ? null : forward.getMaxDrawdownPct(),
                admission.getTraceStatus());
    }

    private boolean isPullbackRelevant(ThemeAdmissionShadowDecisionEntity e) {
        return Boolean.TRUE.equals(e.getWouldCreatePullbackPlan())
                || Boolean.TRUE.equals(e.getNearLimit())
                || hasText(e.getLimitRisk());
    }

    private List<LifecyclePullbackPlanShadowResponse.StatusSummary> byStatus(List<LifecyclePullbackPlanShadowResponse.Item> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> value(r.planStatus(), "NO_PLAN"), LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new LifecyclePullbackPlanShadowResponse.StatusSummary(e.getKey(), e.getValue().size(),
                        average(e.getValue(), LifecyclePullbackPlanShadowResponse.Item::actualReturn5d),
                        average(e.getValue(), LifecyclePullbackPlanShadowResponse.Item::actualReturn10d),
                        average(e.getValue(), LifecyclePullbackPlanShadowResponse.Item::maxDrawdownPct)))
                .sorted(Comparator.comparing(LifecyclePullbackPlanShadowResponse.StatusSummary::count).reversed()
                        .thenComparing(LifecyclePullbackPlanShadowResponse.StatusSummary::planStatus))
                .toList();
    }

    private List<LifecyclePullbackPlanShadowResponse.StageSummary> byStage(List<LifecyclePullbackPlanShadowResponse.Item> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> value(r.lifecycleStage(), "UNKNOWN"), LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new LifecyclePullbackPlanShadowResponse.StageSummary(e.getKey(), e.getValue().size(),
                        e.getValue().stream().filter(r -> Boolean.TRUE.equals(r.wouldCreatePullbackPlan())).count(),
                        e.getValue().stream().filter(r -> Boolean.TRUE.equals(r.nearLimit())).count(),
                        average(e.getValue(), LifecyclePullbackPlanShadowResponse.Item::limitUpDensity),
                        average(e.getValue(), LifecyclePullbackPlanShadowResponse.Item::actualReturn5d),
                        average(e.getValue(), LifecyclePullbackPlanShadowResponse.Item::maxDrawdownPct)))
                .sorted(Comparator.comparing(LifecyclePullbackPlanShadowResponse.StageSummary::count).reversed()
                        .thenComparing(LifecyclePullbackPlanShadowResponse.StageSummary::lifecycleStage))
                .toList();
    }

    private List<String> dataGaps(List<ThemeAdmissionShadowDecisionEntity> admissions,
                                  List<LifecyclePullbackPlanShadowResponse.Item> rows,
                                  Map<LifecycleKey, ThemeLifecycleStateEntity> lifecycle,
                                  Map<ForwardKey, CandidateForwardTrackingEntity> forwards) {
        List<String> gaps = new ArrayList<>();
        if (admissions.isEmpty()) {
            gaps.add("NO_ROWS_IN_REQUESTED_WINDOW:theme_admission_shadow_decision");
        }
        if (!admissions.isEmpty() && rows.isEmpty()) {
            gaps.add("NO_PULLBACK_RELEVANT_ROWS:would_create_pullback_plan_or_near_limit_or_limit_risk");
        }
        if (!rows.isEmpty() && lifecycle.isEmpty()) {
            gaps.add("NO_LIFECYCLE_ROWS_IN_REQUESTED_WINDOW:theme_lifecycle_state");
        }
        if (!rows.isEmpty() && forwards.isEmpty()) {
            gaps.add("NO_FORWARD_TRACKING_ROWS_IN_REQUESTED_WINDOW:candidate_forward_tracking");
        }
        return List.copyOf(gaps);
    }

    private Window window(int days) {
        int requestedDays = Math.max(1, Math.min(days, MAX_REPORT_DAYS));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(requestedDays - 1L);
        return new Window(requestedDays, start, end);
    }

    private static BigDecimal average(List<LifecyclePullbackPlanShadowResponse.Item> rows,
                                      Function<LifecyclePullbackPlanShadowResponse.Item, BigDecimal> extractor) {
        List<BigDecimal> values = rows.stream().map(extractor).filter(Objects::nonNull).toList();
        if (values.isEmpty()) return null;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static boolean gte(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record Window(int requestedDays, LocalDate start, LocalDate end) {}
    private record LifecycleKey(LocalDate tradingDate, String themeTag) {}
    private record ForwardKey(LocalDate tradingDate, String symbol) {}
}
