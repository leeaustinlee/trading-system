package com.austin.trading.engine;

import com.austin.trading.domain.enums.AllocationAction;
import com.austin.trading.domain.enums.AllocationMode;
import com.austin.trading.dto.internal.CapitalAllocationInput;
import com.austin.trading.dto.internal.CapitalAllocationInput.AllocationIntent;
import com.austin.trading.dto.internal.CapitalAllocationResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.11 CapitalAllocationEngine：以「每筆最大風險金額」為核心的資金配置建議。
 *
 * <h3>計算流程</h3>
 * <pre>
 *   riskPerShare   = entryPrice − stopLoss                （&lt;=0 → RISK_BLOCK/INVALID_STOP_LOSS）
 *   maxLossAmount  = accountEquity × riskPctPerTrade
 *   amountByRisk   = floor(maxLossAmount / riskPerShare) × entryPrice
 *   amountByPos    = accountEquity × singlePositionLimit
 *   amountByCash   = availableCash − accountEquity × cashReservePct
 *   amountByMarket = accountEquity × marketExposureLimit − currentPortfolioExposure
 *   amountByTheme  = accountEquity × themeExposureLimit   − currentThemeExposure
 *   suggestedAmount = min(above 5)
 *   suggestedShares = floor(suggestedAmount / entryPrice / lotSize) × lotSize
 * </pre>
 *
 * <p>所有分支皆 conservative：任一 cap ≤ 0 直接擋；小於 min_trade_amount 轉 CASH_RESERVE；
 * regime=PANIC 時 marketExposureLimit=0 自然擋住。</p>
 *
 * <p>不下單。不改持倉。不改 DB。僅產 trace + reasons + warnings 給上層決定是否發 LINE。</p>
 */
@Component
public class CapitalAllocationEngine {

    public CapitalAllocationResult evaluate(CapitalAllocationInput in) {
        Map<String, Object> trace = initTrace(in);
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        AllocationIntent intent = in.intent() == null ? AllocationIntent.OPEN : in.intent();

        // ── REDUCE / SWITCH 特殊路徑（不走單筆風險計算，只給比例建議）──
        if (intent == AllocationIntent.REDUCE || intent == AllocationIntent.SWITCH) {
            return buildReduceSuggestion(in, intent, trace, reasons, warnings);
        }

        // ── OPEN / ADD：風險驅動 sizing ──
        BigDecimal entry = in.entryPrice();
        BigDecimal stop = in.stopLoss();
        if (entry == null || stop == null || entry.signum() <= 0) {
            reasons.add("INVALID_ENTRY_PRICE");
            trace.put("decisionPath", "RISK_BLOCK_ENTRY");
            return buildBlockResult(in, intent, AllocationAction.RISK_BLOCK, trace, reasons, warnings);
        }

        BigDecimal riskPerShare = entry.subtract(stop);
        trace.put("riskPerShare", riskPerShare);
        if (riskPerShare.signum() <= 0) {
            reasons.add("INVALID_STOP_LOSS");
            trace.put("decisionPath", "RISK_BLOCK_STOP");
            return buildBlockResult(in, intent, AllocationAction.RISK_BLOCK, trace, reasons, warnings);
        }

        BigDecimal equity = safePositive(in.accountEquity());
        BigDecimal availCash = safeNonNegative(in.availableCash());
        BigDecimal riskPctPerTrade = safeNonNegative(in.riskPctPerTrade());
        BigDecimal cashReservePct = safeNonNegative(in.cashReservePct());
        BigDecimal singlePosLimit = safeNonNegative(in.singlePositionLimit());
        BigDecimal marketLimit = safeNonNegative(in.marketExposureLimit());
        BigDecimal themeLimit = safeNonNegative(in.themeExposureLimit());
        BigDecimal curPortExp = safeNonNegative(in.currentPortfolioExposure());
        BigDecimal curThemeExp = safeNonNegative(in.currentThemeExposure());
        BigDecimal minTradeAmt = in.minTradeAmount() == null
                ? BigDecimal.ZERO : in.minTradeAmount();

        if (equity.signum() <= 0) {
            warnings.add("EQUITY_FALLBACK_ZERO");
            reasons.add("NO_EQUITY_DATA");
            trace.put("decisionPath", "RISK_BLOCK_EQUITY");
            return buildBlockResult(in, intent, AllocationAction.RISK_BLOCK, trace, reasons, warnings);
        }

        // ── 1. amountByRisk ──
        BigDecimal maxLossAmount = equity.multiply(riskPctPerTrade);
        BigDecimal sharesByRiskRaw = riskPerShare.signum() > 0
                ? maxLossAmount.divide(riskPerShare, 0, RoundingMode.FLOOR)
                : BigDecimal.ZERO;
        BigDecimal amountByRisk = sharesByRiskRaw.multiply(entry);
        trace.put("maxLossAmount", maxLossAmount);
        trace.put("sharesByRisk", sharesByRiskRaw);
        trace.put("amountByRisk", amountByRisk);

        // ── 2. amountByPosition ──
        BigDecimal amountByPosition = equity.multiply(singlePosLimit);
        trace.put("maxAmountByPosition", amountByPosition);

        // ── 3. amountByCash（扣掉保留現金）──
        BigDecimal reserveFromEquity = equity.multiply(cashReservePct);
        BigDecimal amountByCash = availCash.subtract(reserveFromEquity).max(BigDecimal.ZERO);
        trace.put("reserveFromEquity", reserveFromEquity);
        trace.put("maxAmountByCash", amountByCash);
        if (amountByCash.signum() <= 0) {
            reasons.add("CASH_RESERVE_INSUFFICIENT");
            trace.put("decisionPath", "CASH_RESERVE");
            return buildBlockResult(in, intent, AllocationAction.CASH_RESERVE, trace, reasons, warnings);
        }

        // ── 4. amountByMarket ──
        BigDecimal amountByMarket = equity.multiply(marketLimit).subtract(curPortExp);
        trace.put("maxAmountByMarket", amountByMarket);
        if (amountByMarket.signum() <= 0) {
            reasons.add("MARKET_EXPOSURE_LIMIT");
            trace.put("decisionPath", "RISK_BLOCK_MARKET");
            return buildBlockResult(in, intent, AllocationAction.RISK_BLOCK, trace, reasons, warnings);
        }

        // ── 5. amountByTheme ──
        BigDecimal amountByTheme = equity.multiply(themeLimit).subtract(curThemeExp);
        trace.put("maxAmountByTheme", amountByTheme);
        if (amountByTheme.signum() <= 0) {
            reasons.add("THEME_EXPOSURE_LIMIT");
            trace.put("decisionPath", "RISK_BLOCK_THEME");
            return buildBlockResult(in, intent, AllocationAction.RISK_BLOCK, trace, reasons, warnings);
        }

        // ── 6. 取最嚴 cap ──
        BigDecimal suggested = minOf(amountByRisk, amountByPosition, amountByCash, amountByMarket, amountByTheme);
        trace.put("suggestedAmount_preRound", suggested);

        if (suggested.compareTo(minTradeAmt) < 0) {
            reasons.add("BELOW_MIN_TRADE_AMOUNT");
            warnings.add(String.format("suggestedAmount=%s < min=%s", suggested, minTradeAmt));
            trace.put("decisionPath", "CASH_RESERVE_TOO_SMALL");
            return buildBlockResult(in, intent, AllocationAction.CASH_RESERVE, trace, reasons, warnings);
        }

        // ── 7. 股數（依 lotSize 取整，再回推金額）──
        int lotSize = in.lotSize() == null || in.lotSize() <= 0 ? 1 : in.lotSize();
        BigDecimal unitPrice = entry.multiply(BigDecimal.valueOf(lotSize));
        if (unitPrice.signum() <= 0) {
            reasons.add("INVALID_UNIT_PRICE");
            return buildBlockResult(in, intent, AllocationAction.RISK_BLOCK, trace, reasons, warnings);
        }
        int lots = suggested.divide(unitPrice, 0, RoundingMode.FLOOR).intValue();
        int suggestedShares = lots * lotSize;
        BigDecimal finalAmount = entry.multiply(BigDecimal.valueOf(suggestedShares));
        trace.put("lotSize", lotSize);
        trace.put("suggestedShares", suggestedShares);
        trace.put("suggestedAmount", finalAmount);

        if (suggestedShares <= 0) {
            reasons.add("ZERO_SHARES_AFTER_ROUNDING");
            warnings.add(String.format("entry=%s lotSize=%d unitPrice=%s cap=%s",
                    entry, lotSize, unitPrice, suggested));
            trace.put("decisionPath", "CASH_RESERVE_ZERO_SHARES");
            return buildBlockResult(in, intent, AllocationAction.CASH_RESERVE, trace, reasons, warnings);
        }

        BigDecimal estimatedLoss = riskPerShare.multiply(BigDecimal.valueOf(suggestedShares));
        BigDecimal positionPct = finalAmount.divide(equity, 4, RoundingMode.HALF_UP);
        BigDecimal portfolioAfter = curPortExp.add(finalAmount);
        BigDecimal themeAfter = curThemeExp.add(finalAmount);
        trace.put("estimatedLossAmount", estimatedLoss);
        trace.put("positionPctOfEquity", positionPct);
        trace.put("portfolioExposureAfter", portfolioAfter);
        trace.put("themeExposureAfter", themeAfter);

        AllocationAction action = (intent == AllocationIntent.ADD)
                ? AllocationAction.ADD_SIZE_SUGGESTION
                : AllocationAction.BUY_SIZE_SUGGESTION;
        reasons.add(action.name());
        trace.put("decisionPath", action.name());

        return new CapitalAllocationResult(
                in.symbol(), action, in.mode(),
                finalAmount, suggestedShares,
                entry, stop,
                riskPerShare, maxLossAmount, estimatedLoss,
                positionPct, portfolioAfter, themeAfter,
                null,
                List.copyOf(reasons), List.copyOf(warnings),
                Collections.unmodifiableMap(new LinkedHashMap<>(trace))
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // REDUCE / SWITCH 建議（比例而非金額）
    // ══════════════════════════════════════════════════════════════════════

    private CapitalAllocationResult buildReduceSuggestion(
            CapitalAllocationInput in,
            AllocationIntent intent,
            Map<String, Object> trace,
            List<String> reasons,
            List<String> warnings
    ) {
        BigDecimal pct = in.riskPctPerTrade() == null ? new BigDecimal("0.40") : new BigDecimal("0.40");
        // 若 input trace 帶了 reduceHintPct（由 service 塞），用它；否則預設 40%
        Object hinted = in.trace() == null ? null : in.trace().get("capital.reduce_hint_pct");
        if (hinted instanceof BigDecimal b) pct = b;

        AllocationAction action = intent == AllocationIntent.SWITCH
                ? AllocationAction.SWITCH_SIZE_SUGGESTION
                : AllocationAction.REDUCE_SIZE_SUGGESTION;
        reasons.add(action.name());
        trace.put("decisionPath", action.name());
        trace.put("suggestedReducePct", pct);

        return new CapitalAllocationResult(
                in.symbol(), action, in.mode(),
                null, null,
                in.entryPrice(), in.stopLoss(),
                null, null, null,
                null, in.currentPortfolioExposure(), in.currentThemeExposure(),
                pct,
                List.copyOf(reasons), List.copyOf(warnings),
                Collections.unmodifiableMap(new LinkedHashMap<>(trace))
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    private Map<String, Object> initTrace(CapitalAllocationInput in) {
        Map<String, Object> trace = new LinkedHashMap<>();
        if (in.trace() != null) trace.putAll(in.trace());
        trace.put("symbol", in.symbol());
        trace.put("theme", in.theme());
        trace.put("bucket", in.bucket());
        trace.put("mode", in.mode() == null ? null : in.mode().name());
        trace.put("intent", in.intent() == null ? null : in.intent().name());
        trace.put("marketRegime", in.marketRegime());
        trace.put("accountEquity", in.accountEquity());
        trace.put("availableCash", in.availableCash());
        trace.put("riskPctPerTrade", in.riskPctPerTrade());
        trace.put("singlePositionLimit", in.singlePositionLimit());
        trace.put("marketExposureLimit", in.marketExposureLimit());
        trace.put("themeExposureLimit", in.themeExposureLimit());
        trace.put("cashReservePct", in.cashReservePct());
        trace.put("minTradeAmount", in.minTradeAmount());
        trace.put("lotSize", in.lotSize());
        trace.put("entryPrice", in.entryPrice());
        trace.put("stopLoss", in.stopLoss());
        trace.put("currentPortfolioExposure", in.currentPortfolioExposure());
        trace.put("currentThemeExposure", in.currentThemeExposure());
        return trace;
    }

    private CapitalAllocationResult buildBlockResult(
            CapitalAllocationInput in,
            AllocationIntent intent,
            AllocationAction action,
            Map<String, Object> trace,
            List<String> reasons,
            List<String> warnings
    ) {
        return new CapitalAllocationResult(
                in.symbol(), action, in.mode(),
                null, null,
                in.entryPrice(), in.stopLoss(),
                null, null, null,
                null, in.currentPortfolioExposure(), in.currentThemeExposure(),
                null,
                List.copyOf(reasons), List.copyOf(warnings),
                Collections.unmodifiableMap(new LinkedHashMap<>(trace))
        );
    }

    private BigDecimal minOf(BigDecimal... values) {
        BigDecimal min = null;
        for (BigDecimal v : values) {
            if (v == null) continue;
            if (min == null || v.compareTo(min) < 0) min = v;
        }
        return min == null ? BigDecimal.ZERO : min;
    }

    private BigDecimal safePositive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? BigDecimal.ZERO : v;
    }

    private BigDecimal safeNonNegative(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
