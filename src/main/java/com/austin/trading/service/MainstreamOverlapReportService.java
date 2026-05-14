package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MainstreamOverlapReportService {

    private final CandidateStockRepository candidateStockRepository;

    public MainstreamOverlapReportService(CandidateStockRepository candidateStockRepository) {
        this.candidateStockRepository = candidateStockRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recent(int days) {
        int window = days > 0 ? days : 30;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);
        List<CandidateStockEntity> rows =
                candidateStockRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(start, end);
        Map<String, Long> byTheme = rows.stream().collect(Collectors.groupingBy(
                c -> normalizeTheme(c), Collectors.counting()));
        List<String> mainstream = byTheme.entrySet().stream()
                .filter(e -> !"UNKNOWN".equals(e.getKey()) && !"OTHER".equals(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
        long overlap = rows.stream().filter(c -> mainstream.contains(normalizeTheme(c))).count();
        BigDecimal overlapPct = rows.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(overlap).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        List<Map<String, Object>> topThemeByCandidateCount = topThemes(rows, c -> true);
        List<Map<String, Object>> topThemeByBreakoutCount = topThemes(rows, this::isBreakout);
        List<Map<String, Object>> topThemeByContinuationCount = topThemes(rows, this::isContinuation);
        long unmapped = rows.stream().filter(c -> {
            String theme = normalizeTheme(c);
            return "UNKNOWN".equals(theme) || "OTHER".equals(theme);
        }).count();
        BigDecimal unmappedPct = rows.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(unmapped).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        List<Map<String, Object>> topThemeByAmount = byTheme.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("theme", e.getKey(), "count", e.getValue()))
                .toList();
        long breakoutCount = rows.stream().filter(this::isBreakout).count();
        long continuation = rows.stream().filter(this::isContinuation).count();
        List<String> dataGaps = dataGaps(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", Map.of("start", start, "end", end, "days", window));
        out.put("totalCandidates", rows.size());
        out.put("todayMainstreamThemes", mainstream);
        out.put("topThemeByCandidateCount", topThemeByCandidateCount);
        out.put("topThemeByBreakoutCount", topThemeByBreakoutCount);
        out.put("topThemeByContinuationCount", topThemeByContinuationCount);
        out.put("candidateOverlapPct", overlapPct);
        out.put("unmappedPct", unmappedPct);
        out.put("dataGaps", dataGaps);
        out.put("institutionalFlowThemes", "DATA_GAP: no institutional flow theme source is joined in this endpoint");
        out.put("topThemeByAmount", topThemeByAmount);
        out.put("breakoutCount", breakoutCount);
        out.put("continuation", continuation);
        out.put("reasonHints", reasonHints(rows, overlapPct));
        return out;
    }

    private List<String> reasonHints(List<CandidateStockEntity> rows, BigDecimal overlapPct) {
        if (rows.isEmpty()) return List.of("DATA_GAP: no candidate_stock rows in window");
        if (overlapPct.compareTo(new BigDecimal("40")) >= 0) return List.of();
        long unknown = rows.stream().filter(c -> "OTHER".equals(normalizeTheme(c))
                || "UNKNOWN".equals(normalizeTheme(c))).count();
        return List.of(
                unknown > rows.size() / 3 ? "theme mapping 不足" : "theme score 權重不足",
                "momentum gate 未啟用或 gate 太硬",
                "AI analysis 未回灌到 theme/momentum 欄位"
        );
    }

    private List<Map<String, Object>> topThemes(List<CandidateStockEntity> rows,
                                                java.util.function.Predicate<CandidateStockEntity> filter) {
        return rows.stream()
                .filter(filter)
                .collect(Collectors.groupingBy(this::normalizeTheme, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("theme", e.getKey(), "count", e.getValue()))
                .toList();
    }

    private boolean isBreakout(CandidateStockEntity c) {
        String reason = c.getReason() == null ? "" : c.getReason();
        return reason.contains("突破") || reason.toUpperCase(java.util.Locale.ROOT).contains("BREAKOUT");
    }

    private boolean isContinuation(CandidateStockEntity c) {
        String reason = c.getReason() == null ? "" : c.getReason();
        return c.isMomentumCandidate() || reason.contains("延續") || reason.contains("續強")
                || reason.toUpperCase(java.util.Locale.ROOT).contains("CONTINUATION");
    }

    private List<String> dataGaps(List<CandidateStockEntity> rows) {
        List<String> gaps = new ArrayList<>();
        if (rows.isEmpty()) gaps.add("DATA_GAP: no candidate_stock rows in window");
        long noTheme = rows.stream().filter(c -> c.getThemeTag() == null || c.getThemeTag().isBlank()).count();
        if (noTheme > 0) gaps.add("DATA_GAP: missing themeTag rows=" + noTheme + "; reason keyword fallback used where possible");
        gaps.add("DATA_GAP: institutional flow themes unavailable; endpoint has no institutional flow source");
        return gaps;
    }

    private String normalizeTheme(CandidateStockEntity c) {
        return MainstreamThemeNormalizer.normalize(c.getThemeTag(), c.getReason());
    }
}
