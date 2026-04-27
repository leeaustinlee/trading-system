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
 * v2 Theme Engine：Shadow Mode 差異分類器。
 *
 * <p>輸入 legacy 決策 + PR4 {@link ThemeGateTraceResultDto}，逐檔產出 {@link DecisionDiffType}
 * 並寫入 {@code theme_shadow_decision_log}。</p>
 *
 * <h3>P0.2 修正：write-on-every-comparison（不只在差異時寫）</h3>
 * <p>當 {@code theme.shadow_mode.enabled=true} 時，每一筆 candidate × theme 比對<strong>都會落 log</strong>，
 * {@code decision_diff_type} 標記為下列五類之一以供盤後分析：</p>
 * <table>
 *   <tr><th>legacy</th><th>theme</th><th>條件</th><th>diffType</th></tr>
 *   <tr><td>ENTER</td><td>PASS</td><td>|scoreDiff| &lt;= 0.1</td><td>SAME_DECISION_SAME_SCORE</td></tr>
 *   <tr><td>ENTER</td><td>PASS</td><td>|scoreDiff| &gt; 0.1</td><td>SAME_DECISION_SCORE_DIFF</td></tr>
 *   <tr><td>非ENTER</td><td>BLOCK/WAIT</td><td>|scoreDiff| &lt;= 0.1</td><td>SAME_DECISION_SAME_SCORE</td></tr>
 *   <tr><td>非ENTER</td><td>BLOCK/WAIT</td><td>|scoreDiff| &gt; 0.1</td><td>SAME_DECISION_SCORE_DIFF</td></tr>
 *   <tr><td>ENTER</td><td>BLOCK</td><td>—</td><td>V2_VETO_LEGACY_PASS</td></tr>
 *   <tr><td>非ENTER</td><td>PASS</td><td>—</td><td>LEGACY_VETO_V2_PASS</td></tr>
 *   <tr><td>ENTER</td><td>WAIT</td><td>—</td><td>DIFF_DECISION</td></tr>
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

    /** P0.2：score 視為 "相同" 的容差。 */
    static final BigDecimal SAME_SCORE_TOLERANCE = new BigDecimal("0.1");

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
     * @return               聚合統計 + 分類 count
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
            BigDecimal legacyScore = in.legacyFinalScore();
            BigDecimal themeScore = in.themeGateTrace().themeFinalScore();
            BigDecimal scoreDiff = (legacyScore != null && themeScore != null)
                    ? themeScore.subtract(legacyScore) : null;

            DecisionDiffType diffType = classify(legacyDecision, themeDecision, scoreDiff);
            counters.merge(diffType, 1, Integer::sum);

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
    // 核心分類邏輯（P0.2）
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

    /**
     * P0.2：write-on-every-comparison 分類器。
     * <ul>
     *   <li>同決策（legacy ENTER ↔ theme PASS、legacy 非ENTER ↔ theme 非PASS）→ 比 score：
     *       |diff| &lt;= 0.1 ⇒ {@code SAME_DECISION_SAME_SCORE}；否則 {@code SAME_DECISION_SCORE_DIFF}</li>
     *   <li>恰一邊 veto：
     *       legacy ENTER + theme BLOCK ⇒ {@code V2_VETO_LEGACY_PASS}；
     *       legacy 非ENTER + theme PASS ⇒ {@code LEGACY_VETO_V2_PASS}</li>
     *   <li>其他不一致（legacy ENTER + theme WAIT）⇒ {@code DIFF_DECISION}</li>
     * </ul>
     */
    static DecisionDiffType classify(String legacyDecision, String themeDecision, BigDecimal scoreDiff) {
        boolean legacyEnter = "ENTER".equals(legacyDecision);
        boolean themePass   = "PASS".equals(themeDecision);
        boolean themeBlock  = "BLOCK".equals(themeDecision);

        // 同方向（legacy ENTER ↔ theme PASS、或 legacy 非ENTER ↔ theme 非PASS）→ 比 score
        if (legacyEnter == themePass) {
            BigDecimal absDiff = scoreDiff == null ? BigDecimal.ZERO : scoreDiff.abs();
            return absDiff.compareTo(SAME_SCORE_TOLERANCE) <= 0
                    ? DecisionDiffType.SAME_DECISION_SAME_SCORE
                    : DecisionDiffType.SAME_DECISION_SCORE_DIFF;
        }
        // legacy ENTER + theme BLOCK：v2 單邊 veto
        if (legacyEnter && themeBlock) {
            return DecisionDiffType.V2_VETO_LEGACY_PASS;
        }
        // legacy 非ENTER + theme PASS：legacy 單邊 veto
        if (!legacyEnter && themePass) {
            return DecisionDiffType.LEGACY_VETO_V2_PASS;
        }
        // 其餘（legacy ENTER + theme WAIT）⇒ DIFF_DECISION
        return DecisionDiffType.DIFF_DECISION;
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
