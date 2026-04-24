package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto;
import com.austin.trading.dto.internal.ThemeContextDto;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import com.austin.trading.engine.ThemeGateTraceEngine;
import com.austin.trading.engine.ThemeGateTraceEngine.Input;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v2 Theme Engine PR4：Gate trace 協調層。
 *
 * <p>對外提供一個方法 {@link #traceCandidates}，內部完成：</p>
 * <ol>
 *   <li>load snapshot（PR2）</li>
 *   <li>load Claude research（PR3）</li>
 *   <li>merge（PR3）</li>
 *   <li>per-candidate 呼叫 {@link ThemeGateTraceEngine}</li>
 * </ol>
 *
 * <p>整個過程為 <strong>trace-only</strong>，回傳結果僅供 FinalDecisionService 附到 decisionTrace，
 * 絕不影響 legacy decision。</p>
 *
 * <h3>Flag 控制</h3>
 * <ul>
 *   <li>{@code theme.gate.trace.enabled=false} → 完全跳過（{@link Outcome#disabled()} 回 empty）</li>
 *   <li>{@code theme.engine.v2.enabled=false} → snapshot service 回 DISABLED；merge 時 snapshot 為空 → context 全 null → gate G2/G3 WAIT</li>
 * </ul>
 */
@Service
public class ThemeGateOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ThemeGateOrchestrator.class);

    private final ThemeSnapshotProperties properties;
    private final ThemeSnapshotService snapshotService;
    private final ClaudeThemeResearchParserService claudeParser;
    private final ThemeContextMergeService mergeService;
    private final ThemeGateTraceEngine engine;

    public ThemeGateOrchestrator(ThemeSnapshotProperties properties,
                                  ThemeSnapshotService snapshotService,
                                  ClaudeThemeResearchParserService claudeParser,
                                  ThemeContextMergeService mergeService,
                                  ThemeGateTraceEngine engine) {
        this.properties = properties;
        this.snapshotService = snapshotService;
        this.claudeParser = claudeParser;
        this.mergeService = mergeService;
        this.engine = engine;
    }

    /**
     * 對一批 candidate 跑 gate trace。flag 關閉時立即回空 Outcome。
     *
     * @param candidates 每筆 candidate 的 engine input（symbol 必填、themeContext 由 orchestrator 填回）
     * @return 包含每檔 gate trace + snapshot/claude/merge 的 load status
     */
    public Outcome traceCandidates(List<CandidateProbe> candidates) {
        if (!properties.gateTraceEnabled()) {
            return Outcome.disabled();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Outcome.empty("no candidates");
        }

        // 1. load snapshot
        ThemeSnapshotService.LoadResult snapshotLoad = snapshotService.getCurrentSnapshot();
        // 2. load Claude research (optional; fall back to null data)
        ClaudeThemeResearchParserService.LoadResult claudeLoad = claudeParser.loadCurrent();

        ThemeSnapshotV2Dto snapshot = snapshotLoad.snapshot().orElse(null);
        ClaudeThemeResearchOutputDto claudeData = claudeLoad.data().orElse(null);

        // 3. merge
        ThemeContextMergeService.MergeResult merge = mergeService.merge(snapshot, claudeData);

        // 4. per-candidate gate trace
        List<ThemeGateTraceResultDto> results = new ArrayList<>(candidates.size());
        List<String> fallbackWarnings = new ArrayList<>();
        for (CandidateProbe probe : candidates) {
            ThemeContextDto ctx = null;
            if (probe.themeTag() != null) {
                ctx = merge.findBySymbolAndTheme(probe.symbol(), probe.themeTag()).orElse(null);
            }
            if (ctx == null) {
                // fallback：不論 themeTag 有無，拿 symbol 底下 themeStrength 最高的 context
                // 原因：probe.themeTag 可能永遠為 null（FinalDecisionService 目前尚未推斷），
                // 若不 fallback，G2/G3 會全 WAIT、trace 無意義
                ctx = merge.findBySymbol(probe.symbol()).stream()
                        .max(Comparator.comparing(
                                (ThemeContextDto c) -> c.themeStrength() == null ? BigDecimal.valueOf(-1) : c.themeStrength()))
                        .orElse(null);
                if (ctx != null) {
                    fallbackWarnings.add("THEME_CONTEXT_FALLBACK_BY_SYMBOL:" + probe.symbol() + "->" + ctx.themeTag());
                }
            }
            Input input = probe.toEngineInput(ctx, snapshot);
            results.add(engine.evaluate(input));
        }

        List<String> allWarnings = new ArrayList<>(merge.warnings());
        allWarnings.addAll(fallbackWarnings);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("snapshotStatus", snapshotLoad.status());
        summary.put("snapshotTraceKey", snapshotLoad.traceKey());
        summary.put("claudeStatus", claudeLoad.status());
        summary.put("claudeTraceKey", claudeLoad.traceKey());
        summary.put("mergeWarnings", merge.warnings().size());
        summary.put("fallbackBySymbol", fallbackWarnings.size());
        summary.put("mergeRejected", merge.rejectedClaudeEntries().size());
        summary.put("totalCandidates", results.size());
        summary.put("passCount", results.stream().filter(r -> r.passed()).count());

        log.info("[ThemeGateOrchestrator] traced {} candidates snapshotStatus={} claudeStatus={} mergeWarnings={} fallbackBySymbol={} rejected={}",
                results.size(), snapshotLoad.status(), claudeLoad.status(),
                merge.warnings().size(), fallbackWarnings.size(), merge.rejectedClaudeEntries().size());

        return new Outcome(
                true,
                Collections.unmodifiableList(results),
                snapshotLoad.status().name(),
                snapshotLoad.traceKey(),
                claudeLoad.status().name(),
                claudeLoad.traceKey(),
                Collections.unmodifiableList(allWarnings),
                Collections.unmodifiableList(merge.rejectedClaudeEntries()),
                Collections.unmodifiableMap(summary)
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper types
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 給 caller 用的輕量輸入，orchestrator 會自行補 {@link ThemeContextDto}。
     * symbol / themeTag 必填（themeTag 由 FinalDecisionService 從 Codex review / candidate 解析）。
     */
    public record CandidateProbe(
            String symbol,
            String themeTag,
            String marketRegime,
            boolean tradeAllowed,
            java.math.BigDecimal riskMultiplier,
            java.math.BigDecimal avgTurnover,
            java.math.BigDecimal volumeRatio,
            java.math.BigDecimal javaScore,
            java.math.BigDecimal claudeScore,
            java.math.BigDecimal codexScore,
            java.math.BigDecimal rr,
            java.math.BigDecimal baseScore,
            int openPositions,
            int maxPositions,
            java.math.BigDecimal availableCash
    ) {
        public Input toEngineInput(ThemeContextDto ctx, ThemeSnapshotV2Dto snapshot) {
            // 若 themeContext 無值但 snapshot 有 market_regime 可覆蓋，保留這裡 probe 帶的值為準
            return new Input(
                    symbol, marketRegime, tradeAllowed, riskMultiplier,
                    ctx, avgTurnover, volumeRatio,
                    javaScore, claudeScore, codexScore,
                    rr, baseScore, openPositions, maxPositions, availableCash
            );
        }
    }

    /** Orchestrator 對外結果。PR4 flag-off 時 {@link #active}=false 且 results 為空。 */
    public record Outcome(
            boolean active,
            List<ThemeGateTraceResultDto> results,
            String snapshotStatus,
            String snapshotTraceKey,
            String claudeStatus,
            String claudeTraceKey,
            List<String> mergeWarnings,
            List<String> mergeRejectedClaudeEntries,
            Map<String, Object> summary
    ) {
        public static Outcome disabled() {
            return new Outcome(false, List.of(), "DISABLED", "THEME_GATE_TRACE_DISABLED",
                    "DISABLED", "THEME_GATE_TRACE_DISABLED",
                    List.of(), List.of(),
                    Map.of("reason", "theme.gate.trace.enabled=false"));
        }

        public static Outcome empty(String reason) {
            return new Outcome(true, List.of(), "N/A", "N/A", "N/A", "N/A",
                    List.of(), List.of(),
                    Map.of("reason", reason));
        }

        public Optional<ThemeGateTraceResultDto> findBySymbol(String symbol) {
            if (symbol == null) return Optional.empty();
            return results.stream().filter(r -> symbol.equals(r.symbol())).findFirst();
        }
    }
}
