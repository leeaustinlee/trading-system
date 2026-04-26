package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * v2.16：MonitorEvaluateRequest 加入 currentPrice / entryZoneLowerBound 兩個 nullable 欄位，
 * 供 swing-friendly cooldown 判斷 B 級候選是否接近進場下緣。舊呼叫端走向下相容 ctor。
 */
public record MonitorEvaluateRequest(
        @NotBlank String marketGrade,
        @NotBlank String decision,
        String marketPhase,
        String previousMonitorMode,
        String previousEventType,
        LocalTime evaluationTime,
        boolean hasPosition,
        boolean hasCandidate,
        boolean hasCriticalEvent,
        String decisionLock,
        String timeDecayStage,
        // ── v2.16 Batch C：B 級補進候選用 ────────────────────────────
        BigDecimal currentPrice,           // 候選股當前價（可 null）
        BigDecimal entryZoneLowerBound     // 候選股進場下緣（可 null）
) {
    /** 向下相容 ctor（v2.15 之前無 currentPrice / entryZoneLowerBound）。 */
    public MonitorEvaluateRequest(
            String marketGrade,
            String decision,
            String marketPhase,
            String previousMonitorMode,
            String previousEventType,
            LocalTime evaluationTime,
            boolean hasPosition,
            boolean hasCandidate,
            boolean hasCriticalEvent,
            String decisionLock,
            String timeDecayStage
    ) {
        this(marketGrade, decision, marketPhase, previousMonitorMode, previousEventType,
                evaluationTime, hasPosition, hasCandidate, hasCriticalEvent,
                decisionLock, timeDecayStage,
                null, null);
    }
}
