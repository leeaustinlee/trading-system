package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.dto.internal.GateTraceRecordDto;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v2 Theme Engine PR6：Phase 3 live decision override。
 *
 * <p>預設關閉（{@code theme.live_decision.enabled=false}）。開啟後規則：</p>
 * <ul>
 *   <li>對 merged selected 逐檔檢查 PR4 {@link ThemeGateTraceResultDto} overallOutcome</li>
 *   <li><strong>BLOCK</strong> → 從 merged 移除；若全部被移光，legacy ENTER 降級為 REST</li>
 *   <li><strong>WAIT</strong> → 預設 pass-through（保守）。僅 {@code theme.live_decision.wait_override.enabled=true}
 *       時才會同樣被移除（PR6 不啟用此路徑，為未來擴充保留）</li>
 *   <li><strong>PASS</strong> / 無 theme trace → 維持原 legacy decision</li>
 * </ul>
 *
 * <h3>Legacy 保留原則</h3>
 * <p>Service 本身不寫 DB 也不直接改 response；<strong>回傳新的 merged 與 code</strong> 給 caller，
 * 以及 {@link Result#trace} 供 caller 塞到 tracePayload。caller 必須把 legacy 原值
 * （{@code legacyFinalDecisionCode} / {@code legacyMerged}）另存在 trace 供回溯。</p>
 *
 * <h3>Rollback</h3>
 * <p>關掉 {@code theme.live_decision.enabled} 即立即回退到 legacy decision，無需資料庫變更。</p>
 */
@Service
public class ThemeLiveDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ThemeLiveDecisionService.class);

    private final ThemeSnapshotProperties properties;

    public ThemeLiveDecisionService(ThemeSnapshotProperties properties) {
        this.properties = properties;
    }

    /**
     * 對 legacy merged 套用 theme override。
     *
     * @param legacyFinalDecisionCode  Legacy engine 決定的 ENTER / WAIT / REST
     * @param legacyMerged             Legacy engine 選出的 symbol 清單（可為 empty）
     * @param themeOutcome             PR4 gate trace outcome；active=false 時一律 pass-through
     * @return 覆寫後的 {@link Result}；未動時 {@link Result#changed()} 為 false
     */
    public Result apply(String legacyFinalDecisionCode,
                         List<FinalDecisionSelectedStockResponse> legacyMerged,
                         ThemeGateOrchestrator.Outcome themeOutcome) {
        String originalCode = legacyFinalDecisionCode;
        List<FinalDecisionSelectedStockResponse> originalMerged = safeList(legacyMerged);

        // Flag off → pass-through（不回寫任何 trace，caller 只需略過 override）
        if (!properties.liveDecisionEnabled()) {
            return Result.passthrough(originalCode, originalMerged, "FLAG_OFF");
        }
        if (themeOutcome == null || !themeOutcome.active()) {
            return Result.passthrough(originalCode, originalMerged, "THEME_OUTCOME_INACTIVE");
        }
        if (originalMerged.isEmpty()) {
            return Result.passthrough(originalCode, originalMerged, "LEGACY_MERGED_EMPTY");
        }

        boolean waitOverride = properties.liveDecisionWaitOverrideEnabled();

        List<FinalDecisionSelectedStockResponse> kept = new ArrayList<>(originalMerged.size());
        List<Map<String, Object>> removedSymbols = new ArrayList<>();

        for (FinalDecisionSelectedStockResponse s : originalMerged) {
            ThemeGateTraceResultDto trace = themeOutcome.findBySymbol(s.stockCode()).orElse(null);
            Result.Decision decision = evaluate(trace, waitOverride);
            if (decision.keep()) {
                kept.add(s);
            } else {
                removedSymbols.add(decision.asRemovalEntry(s, trace));
            }
        }

        if (removedSymbols.isEmpty()) {
            return Result.passthrough(originalCode, originalMerged, "NO_OVERRIDE_APPLIED");
        }

        // Rule：只 BLOCK（或 wait_override 時 WAIT）才能把 ENTER→REST
        String finalCode = originalCode;
        boolean downgraded = false;
        if ("ENTER".equalsIgnoreCase(originalCode) && kept.isEmpty()) {
            finalCode = "REST";
            downgraded = true;
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("liveDecisionEnabled", true);
        trace.put("waitOverrideEnabled", waitOverride);
        trace.put("legacyFinalDecisionCode", originalCode);
        trace.put("newFinalDecisionCode", finalCode);
        trace.put("downgraded", downgraded);
        trace.put("originalMergedCount", originalMerged.size());
        trace.put("keptCount", kept.size());
        trace.put("removedCount", removedSymbols.size());
        trace.put("removedSymbols", removedSymbols);

        log.info("[ThemeLiveDecision] override applied legacyCode={} newCode={} removed={}/{} waitOverride={}",
                originalCode, finalCode, removedSymbols.size(), originalMerged.size(), waitOverride);

        return new Result(true, finalCode, List.copyOf(kept), originalCode, originalMerged,
                removedSymbols, Collections.unmodifiableMap(trace));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 內部評估
    // ══════════════════════════════════════════════════════════════════════

    private static Result.Decision evaluate(ThemeGateTraceResultDto trace, boolean waitOverride) {
        if (trace == null || trace.overallOutcome() == null) {
            return Result.Decision.keep("THEME_TRACE_MISSING");
        }
        return switch (trace.overallOutcome()) {
            case PASS -> Result.Decision.keep("THEME_PASS");
            case BLOCK -> Result.Decision.remove("THEME_BLOCK", firstReason(trace, GateTraceRecordDto.Result.BLOCK));
            case WAIT -> waitOverride
                    ? Result.Decision.remove("THEME_WAIT_OVERRIDE", firstReason(trace, GateTraceRecordDto.Result.WAIT))
                    : Result.Decision.keep("THEME_WAIT_PASSTHROUGH");
            case SKIPPED -> Result.Decision.keep("THEME_SKIPPED");
        };
    }

    private static String firstReason(ThemeGateTraceResultDto trace, GateTraceRecordDto.Result match) {
        if (trace.gates() == null) return null;
        return trace.gates().stream()
                .filter(g -> g.result() == match)
                .findFirst()
                .map(g -> g.gateKey() + ":" + g.reason())
                .orElse(null);
    }

    /**
     * 從 PR4 trace 擷取題材 context，供 LINE formatter 呈現。
     * 先讀 G2_THEME_VETO payload（含 themeTag / themeStrength），再補 G3 的 stage / rotation / crowding。
     */
    static Map<String, Object> extractThemeContext(ThemeGateTraceResultDto trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (trace == null) return out;
        Map<String, Object> g2p = payloadOf(trace, "G2_THEME_VETO");
        Map<String, Object> g3p = payloadOf(trace, "G3_THEME_ROTATION");
        Object themeTag = g2p.get("themeTag");
        if (themeTag == null) themeTag = g3p.get("themeTag");
        Object strength = g2p.get("themeStrength");
        if (strength == null) strength = g3p.get("themeStrength");
        if (themeTag != null)    out.put("themeTag", themeTag);
        if (strength != null)    out.put("themeStrength", strength);
        Object stage = g3p.get("trendStage");
        Object rotation = g3p.get("rotationSignal");
        Object crowding = g3p.get("crowdingRisk");
        if (stage != null)       out.put("trendStage", stage);
        if (rotation != null)    out.put("rotationSignal", rotation);
        if (crowding != null)    out.put("crowdingRisk", crowding);
        return out;
    }

    private static Map<String, Object> payloadOf(ThemeGateTraceResultDto t, String gateKey) {
        GateTraceRecordDto g = t.findGate(gateKey);
        return (g == null || g.payload() == null) ? Map.of() : g.payload();
    }

    private static <T> List<T> safeList(List<T> in) {
        return in == null ? List.of() : in;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Result type
    // ══════════════════════════════════════════════════════════════════════

    public record Result(
            boolean changed,
            String finalDecisionCode,
            List<FinalDecisionSelectedStockResponse> merged,
            String legacyFinalDecisionCode,
            List<FinalDecisionSelectedStockResponse> legacyMerged,
            List<Map<String, Object>> removedSymbols,
            Map<String, Object> trace
    ) {
        public static Result passthrough(String code,
                                          List<FinalDecisionSelectedStockResponse> merged,
                                          String reason) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("liveDecisionEnabled", false);
            t.put("reason", reason);
            return new Result(false, code, merged, code, merged,
                    List.of(), Collections.unmodifiableMap(t));
        }

        record Decision(boolean keep, String reasonCode, String detail) {
            static Decision keep(String code) { return new Decision(true, code, null); }
            static Decision remove(String code, String detail) { return new Decision(false, code, detail); }

            Map<String, Object> asRemovalEntry(FinalDecisionSelectedStockResponse s,
                                                ThemeGateTraceResultDto trace) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", s.stockCode());
                m.put("reasonCode", reasonCode);
                m.put("detail", detail);         // e.g. "G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN"
                m.put("vetoReason", detail);     // alias；對齊 spec 欄位名
                m.putAll(extractThemeContext(trace));
                return m;
            }
        }
    }
}
