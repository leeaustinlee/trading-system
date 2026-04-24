package com.austin.trading.dto.internal;

import com.austin.trading.dto.internal.GateTraceRecordDto.Result;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v2 Theme Engine PR4：單一 candidate 跑完 8 gates 後的 aggregate 結果。
 *
 * <p>PR4 trace-only：{@link #overallOutcome()} 只做紀錄用，不被 FinalDecision response 消費。
 * PR5 shadow report 會把 overallOutcome mapping 到 ENTER/WAIT/REST 以和 legacy 比對。</p>
 */
public record ThemeGateTraceResultDto(
        String symbol,
        List<GateTraceRecordDto> gates,
        Result overallOutcome,
        /** G5 的 multiplier（spec §5 公式 0.6 + 0.04×themeStrength +/- stage bonus）；PASS 時才算。 */
        BigDecimal themeMultiplier,
        /** baseScore × multiplier clamp 0-10；G8 使用。 */
        BigDecimal themeFinalScore,
        /** CrowdingRisk → size factor (LOW 1.0 / MID 0.85 / HIGH 0.65 / UNKNOWN 0.65)；G7 使用。 */
        BigDecimal themeSizeFactor,
        String summary,
        Map<String, Object> extras
) {
    public boolean passed() { return overallOutcome == Result.PASS; }

    public GateTraceRecordDto findGate(String gateKey) {
        if (gates == null || gateKey == null) return null;
        return gates.stream().filter(g -> gateKey.equals(g.gateKey())).findFirst().orElse(null);
    }
}
