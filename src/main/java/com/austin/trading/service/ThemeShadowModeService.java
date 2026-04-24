package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.domain.enums.DecisionDiffType;
import com.austin.trading.dto.internal.GateTraceRecordDto;
import com.austin.trading.dto.internal.GateTraceRecordDto.Result;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.entity.ThemeShadowDecisionLogEntity;
import com.austin.trading.repository.ThemeShadowDecisionLogRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v2 Theme Engine PR5：Shadow Mode 差異分類器。
 *
 * <p>輸入 legacy 決策 + PR4 {@link ThemeGateTraceResultDto}，逐檔產出 6 種
 * {@link DecisionDiffType} 並寫入 {@code theme_shadow_decision_log}。</p>
 *
 * <h3>分類規則（legacy 正規化為 ENTER / 非ENTER；theme 由 overallOutcome 映射 PASS/WAIT/BLOCK）</h3>
 * <table>
 *   <tr><th>legacy</th><th>theme</th><th>diffType</th></tr>
 *   <tr><td>ENTER</td><td>PASS</td><td>SAME_BUY</td></tr>
 *   <tr><td>ENTER</td><td>BLOCK</td><td>LEGACY_BUY_THEME_BLOCK</td></tr>
 *   <tr><td>ENTER</td><td>WAIT</td><td>CONFLICT_REVIEW_REQUIRED</td></tr>
 *   <tr><td>非ENTER</td><td>PASS</td><td>LEGACY_WAIT_THEME_BUY</td></tr>
 *   <tr><td>非ENTER</td><td>BLOCK</td><td>BOTH_BLOCK</td></tr>
 *   <tr><td>非ENTER</td><td>WAIT</td><td>SAME_WAIT</td></tr>
 * </table>
 *
 * <p>Trace-only：即使 flag 開啟，也<strong>不</strong>回寫任何 live decision 欄位；
 * 僅落 log 供 {@link ThemeShadowReportService} 生日報。</p>
 *
 * <h3>前置條件</h3>
 * <p>Shadow 輸出完全依賴 PR4 {@link ThemeGateTraceResultDto}。因此
 * {@code theme.shadow_mode.enabled=true} 時<strong>必須同時</strong>開啟
 * {@code theme.gate.trace.enabled=true}。若 gate trace 關閉，caller 傳入的 inputs
 * 會是空的（{@code ThemeGateOrchestrator} Outcome.active=false），
 * {@code theme_shadow_decision_log} 不會有任何紀錄。</p>
 */
@Service
public class ThemeShadowModeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeShadowModeService.class);

    /** {@code theme_veto_reason} 欄位長度上限（對齊 Entity {@code length=80}）。 */
    private static final int VETO_REASON_MAX_LEN = 80;

    private final ThemeSnapshotProperties properties;
    private final ThemeShadowDecisionLogRepository logRepo;
    private final ObjectMapper objectMapper;

    public ThemeShadowModeService(ThemeSnapshotProperties properties,
                                   ThemeShadowDecisionLogRepository logRepo) {
        this.properties = properties;
        this.logRepo = logRepo;
        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 對一批候選股做 shadow diff。flag 關閉時直接 short-circuit 回 {@link RunResult#disabled()}。
     *
     * @param tradingDate    交易日
     * @param marketRegime   市場狀態（落 log 用；nullable）
     * @param inputs         每筆 candidate 的 legacy 結果 + PR4 gate trace
     * @return               聚合統計 + 6 分類 count
     */
    public RunResult record(LocalDate tradingDate, String marketRegime, List<Input> inputs) {
        if (!properties.shadowModeEnabled()) {
            return RunResult.disabled();
        }
        if (tradingDate == null || inputs == null || inputs.isEmpty()) {
            if (inputs == null || inputs.isEmpty()) {
                log.debug("[ThemeShadowMode] inputs 為空；若 theme.shadow_mode.enabled=true 但無紀錄，"
                        + "請確認 theme.gate.trace.enabled 也已開啟。");
            }
            return RunResult.empty(tradingDate);
        }

        List<ThemeShadowDecisionLogEntity> persisted = new ArrayList<>(inputs.size());
        Map<DecisionDiffType, Integer> counters = new EnumMap<>(DecisionDiffType.class);
        for (DecisionDiffType t : DecisionDiffType.values()) counters.put(t, 0);

        for (Input in : inputs) {
            if (in == null || in.symbol() == null || in.themeGateTrace() == null) continue;

            String legacyDecision = normalizeLegacy(in.legacyDecision());
            String themeDecision = mapTheme(in.themeGateTrace().overallOutcome());
            DecisionDiffType diffType = classify(legacyDecision, themeDecision);
            counters.merge(diffType, 1, Integer::sum);

            BigDecimal legacyScore = in.legacyFinalScore();
            BigDecimal themeScore = in.themeGateTrace().themeFinalScore();
            BigDecimal scoreDiff = (legacyScore != null && themeScore != null)
                    ? themeScore.subtract(legacyScore) : null;
            String vetoReason = truncate(extractVetoReason(in.themeGateTrace()), VETO_REASON_MAX_LEN);
            String themeTraceJson = serialize(in.themeGateTrace());

            ThemeShadowDecisionLogEntity e = logRepo
                    .findByTradingDateAndSymbol(tradingDate, in.symbol())
                    .orElseGet(ThemeShadowDecisionLogEntity::new);
            e.setTradingDate(tradingDate);
            e.setSymbol(in.symbol());
            e.setMarketRegime(marketRegime);
            e.setLegacyFinalScore(legacyScore);
            e.setThemeFinalScore(themeScore);
            e.setScoreDiff(scoreDiff);
            e.setLegacyDecision(legacyDecision);
            e.setThemeDecision(themeDecision);
            e.setThemeVetoReason(vetoReason);
            e.setDecisionDiffType(diffType.name());
            e.setLegacyTraceJson(in.legacyTraceJson());
            e.setThemeTraceJson(themeTraceJson);
            e.setGeneratedBy("FinalDecisionService");
            e.setGeneratedAt(LocalDateTime.now());

            persisted.add(logRepo.save(e));
        }

        log.info("[ThemeShadowMode] date={} total={} counts={}",
                tradingDate, persisted.size(), counters);

        return new RunResult(
                true, tradingDate, persisted.size(),
                Collections.unmodifiableMap(counters),
                Collections.unmodifiableList(persisted));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 核心分類邏輯
    // ══════════════════════════════════════════════════════════════════════

    static String normalizeLegacy(String raw) {
        if (raw == null || raw.isBlank()) return "WAIT";
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return v.equals("ENTER") ? "ENTER" : "WAIT";
    }

    static String mapTheme(Result outcome) {
        if (outcome == null) return "WAIT";
        return switch (outcome) {
            case PASS -> "PASS";
            case BLOCK -> "BLOCK";
            case WAIT, SKIPPED -> "WAIT";
        };
    }

    static DecisionDiffType classify(String legacyDecision, String themeDecision) {
        boolean legacyEnter = "ENTER".equals(legacyDecision);
        return switch (themeDecision) {
            case "PASS"  -> legacyEnter ? DecisionDiffType.SAME_BUY
                                        : DecisionDiffType.LEGACY_WAIT_THEME_BUY;
            case "BLOCK" -> legacyEnter ? DecisionDiffType.LEGACY_BUY_THEME_BLOCK
                                        : DecisionDiffType.BOTH_BLOCK;
            case "WAIT"  -> legacyEnter ? DecisionDiffType.CONFLICT_REVIEW_REQUIRED
                                        : DecisionDiffType.SAME_WAIT;
            default -> DecisionDiffType.CONFLICT_REVIEW_REQUIRED;
        };
    }

    /** 找第一個 BLOCK gate 的 reason；無則找第一個 WAIT（data missing 之類）；都無則 null。 */
    static String extractVetoReason(ThemeGateTraceResultDto trace) {
        if (trace == null || trace.gates() == null) return null;
        for (GateTraceRecordDto g : trace.gates()) {
            if (g.result() == Result.BLOCK) return g.gateKey() + ":" + g.reason();
        }
        for (GateTraceRecordDto g : trace.gates()) {
            if (g.result() == Result.WAIT) return g.gateKey() + ":" + g.reason();
        }
        return null;
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }

    private String serialize(ThemeGateTraceResultDto trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            log.warn("[ThemeShadowMode] serialize theme trace failed: {}", e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 型別
    // ══════════════════════════════════════════════════════════════════════

    /** 一檔 candidate 的 shadow 輸入。 */
    public record Input(
            String symbol,
            String legacyDecision,
            BigDecimal legacyFinalScore,
            ThemeGateTraceResultDto themeGateTrace,
            String legacyTraceJson
    ) {}

    /** shadow 執行結果摘要，僅供 caller log / report 使用。 */
    public record RunResult(
            boolean active,
            LocalDate tradingDate,
            int totalRecorded,
            Map<DecisionDiffType, Integer> counts,
            List<ThemeShadowDecisionLogEntity> entries
    ) {
        public static RunResult disabled() {
            Map<DecisionDiffType, Integer> empty = new LinkedHashMap<>();
            for (DecisionDiffType t : DecisionDiffType.values()) empty.put(t, 0);
            return new RunResult(false, null, 0, Collections.unmodifiableMap(empty), List.of());
        }

        public static RunResult empty(LocalDate date) {
            Map<DecisionDiffType, Integer> empty = new LinkedHashMap<>();
            for (DecisionDiffType t : DecisionDiffType.values()) empty.put(t, 0);
            return new RunResult(true, date, 0, Collections.unmodifiableMap(empty), List.of());
        }
    }
}
