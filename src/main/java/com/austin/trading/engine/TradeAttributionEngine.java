package com.austin.trading.engine;

import com.austin.trading.dto.internal.TradeAttributionInput;
import com.austin.trading.dto.internal.TradeAttributionOutput;
import com.austin.trading.entity.PositionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;

/**
 * P1.2 Trade Attribution Engine — stateless, pure.
 *
 * <h3>Metrics computed</h3>
 * <ul>
 *   <li>{@code delayPct}    = (actualEntry - idealEntry) / idealEntry * 100</li>
 *   <li>{@code pnlPct}      = (exitPrice - entryPrice) / entryPrice * 100</li>
 *   <li>{@code timingQuality} GOOD / FAIR / POOR based on delayPct</li>
 *   <li>{@code exitQuality}   GOOD / FAIR / POOR based on pnl vs mfe capture ratio</li>
 *   <li>{@code sizingQuality} GOOD / UNKNOWN (sizing data rarely available)</li>
 * </ul>
 */
@Component
public class TradeAttributionEngine {

    private static final Logger log = LoggerFactory.getLogger(TradeAttributionEngine.class);

    public static final String QUALITY_GOOD    = "GOOD";
    public static final String QUALITY_FAIR    = "FAIR";
    public static final String QUALITY_POOR    = "POOR";
    public static final String QUALITY_UNKNOWN = "UNKNOWN";

    private final ObjectMapper objectMapper;

    public TradeAttributionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TradeAttributionOutput evaluate(TradeAttributionInput in) {
        if (in == null || in.position() == null) return null;

        PositionEntity pos = in.position();
        BigDecimal actualEntry = pos.getAvgCost();
        BigDecimal exitPrice   = pos.getClosePrice();

        BigDecimal pnlPct    = computePnlPct(actualEntry, exitPrice);
        BigDecimal delayPct  = computeDelayPct(in.idealEntryPrice(), actualEntry);

        String timingQuality = assessTiming(delayPct, in.maePct());
        String exitQuality   = assessExit(pnlPct, in.mfePct());
        String sizingQuality = assessSizing(in.approvedRiskAmount(), pos.getQty(), actualEntry);

        String payload = buildPayload(in, pnlPct, delayPct, timingQuality, exitQuality, sizingQuality);

        log.debug("[TradeAttribution] {} timing={} exit={} delay={}%",
                pos.getSymbol(), timingQuality, exitQuality,
                delayPct != null ? delayPct.toPlainString() : "N/A");

        return new TradeAttributionOutput(
                pos.getId(), pos.getSymbol(),
                in.entryDate(), in.exitDate(),
                in.setupType(), in.regimeType(), in.themeStage(), in.timingMode(),
                in.idealEntryPrice(), actualEntry, delayPct,
                in.mfePct(), in.maePct(), pnlPct,
                timingQuality, exitQuality, sizingQuality,
                in.regimeDecisionId(), in.setupDecisionId(),
                in.timingDecisionId(), in.themeDecisionId(), in.executionDecisionId(),
                payload);
    }

    // ── Metric computations ───────────────────────────────────────────────────

    BigDecimal computePnlPct(BigDecimal entry, BigDecimal exit) {
        if (entry == null || exit == null || entry.compareTo(BigDecimal.ZERO) == 0) return null;
        return exit.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    BigDecimal computeDelayPct(BigDecimal idealEntry, BigDecimal actualEntry) {
        if (idealEntry == null || actualEntry == null
                || idealEntry.compareTo(BigDecimal.ZERO) == 0) return null;
        return actualEntry.subtract(idealEntry)
                .divide(idealEntry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    // ── Quality assessors ─────────────────────────────────────────────────────

    /**
     * Timing quality: how close to the ideal entry was execution.
     * <ul>
     *   <li>GOOD  — delayPct ≤ 0.5%</li>
     *   <li>FAIR  — 0.5% &lt; delayPct ≤ 2%</li>
     *   <li>POOR  — delayPct &gt; 2%, or mae &gt; 5% (deep adverse early)</li>
     *   <li>UNKNOWN — no ideal entry data</li>
     * </ul>
     */
    String assessTiming(BigDecimal delayPct, BigDecimal maePct) {
        if (delayPct == null) {
            // No ideal entry; degrade if MAE is severe
            if (maePct != null && maePct.abs().compareTo(BigDecimal.valueOf(5)) > 0) return QUALITY_POOR;
            return QUALITY_UNKNOWN;
        }
        double d = delayPct.abs().doubleValue();
        double mae = maePct != null ? maePct.abs().doubleValue() : 0;
        if (d > 2.0 || mae > 5.0) return QUALITY_POOR;
        if (d > 0.5)               return QUALITY_FAIR;
        return QUALITY_GOOD;
    }

    /**
     * Exit quality: what fraction of the best available profit was captured.
     * <ul>
     *   <li>GOOD  — pnlPct ≥ 70% of mfePct</li>
     *   <li>FAIR  — 30% ≤ pnlPct &lt; 70% of mfePct</li>
     *   <li>POOR  — pnlPct &lt; 30% of mfePct, or negative pnl</li>
     *   <li>UNKNOWN — mfePct missing</li>
     * </ul>
     */
    String assessExit(BigDecimal pnlPct, BigDecimal mfePct) {
        if (pnlPct == null) return QUALITY_UNKNOWN;
        if (pnlPct.compareTo(BigDecimal.ZERO) < 0) return QUALITY_POOR;
        if (mfePct == null || mfePct.compareTo(BigDecimal.ZERO) <= 0) return QUALITY_UNKNOWN;
        double capture = pnlPct.doubleValue() / mfePct.doubleValue();
        if (capture >= 0.7) return QUALITY_GOOD;
        if (capture >= 0.3) return QUALITY_FAIR;
        return QUALITY_POOR;
    }

    String assessSizing(BigDecimal approvedRisk, BigDecimal qty, BigDecimal entry) {
        if (approvedRisk == null || qty == null || entry == null) return QUALITY_UNKNOWN;
        return QUALITY_GOOD; // placeholder — detailed sizing analysis in P2
    }

    // ── Payload ───────────────────────────────────────────────────────────────

    private String buildPayload(TradeAttributionInput in, BigDecimal pnlPct,
                                 BigDecimal delayPct, String timing, String exit, String sizing) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("symbol",         in.position().getSymbol());
            n.put("setupType",      in.setupType());
            n.put("regimeType",     in.regimeType());
            n.put("themeStage",     in.themeStage());
            n.put("timingMode",     in.timingMode());
            n.put("pnlPct",         pnlPct   != null ? pnlPct.toPlainString()   : null);
            n.put("delayPct",       delayPct != null ? delayPct.toPlainString()  : null);
            n.put("mfePct",         in.mfePct()  != null ? in.mfePct().toPlainString()  : null);
            n.put("maePct",         in.maePct()  != null ? in.maePct().toPlainString()  : null);
            n.put("timingQuality",  timing);
            n.put("exitQuality",    exit);
            n.put("sizingQuality",  sizing);
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[TradeAttributionEngine] payload serialization failed");
            return "{}";
        }
    }
}
