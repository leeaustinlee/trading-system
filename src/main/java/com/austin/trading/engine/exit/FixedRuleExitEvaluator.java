package com.austin.trading.engine.exit;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v1 固定規則 evaluator(Paper Trade Phase 1):
 *
 * <ol>
 *   <li>停損優先:bar.low &le; stopLoss → STOP_LOSS @ stopLossPrice</li>
 *   <li>停利 2:bar.high &ge; target2 → TARGET_2 @ target2Price</li>
 *   <li>停利 1:bar.high &ge; target1 → TARGET_1 @ target1Price</li>
 *   <li>時間出場:holdingDays &ge; maxHoldingDays → TIME_EXIT @ close</li>
 * </ol>
 *
 * <p>停損優先於停利是回測標準慣例:同一根 K 同時觸碰兩端時,假設較差結果(
 * 因為實盤無法保證會先打到哪個價位)。</p>
 *
 * <p>MFE/MAE 都用 entry price 為基底計算 (bar.high - entry) / entry %。</p>
 */
@Component
public class FixedRuleExitEvaluator implements ExitRuleEvaluator {

    @Override
    public ExitDecision evaluate(EntrySnapshot entry, DailyBar bar) {
        if (entry == null || bar == null) return ExitDecision.hold(null, null);
        if (entry.entryPrice() == null || entry.entryPrice().signum() <= 0) {
            return ExitDecision.hold(null, null);
        }

        BigDecimal mfe = pct(bar.high(), entry.entryPrice());
        BigDecimal mae = pct(bar.low(),  entry.entryPrice());

        // 1. 停損
        if (entry.stopLossPrice() != null && bar.low() != null
                && bar.low().compareTo(entry.stopLossPrice()) <= 0) {
            return new ExitDecision(true, ExitReason.STOP_LOSS, entry.stopLossPrice(), mfe, mae);
        }
        // 2. T2
        if (entry.target2Price() != null && bar.high() != null
                && bar.high().compareTo(entry.target2Price()) >= 0) {
            return new ExitDecision(true, ExitReason.TARGET_2, entry.target2Price(), mfe, mae);
        }
        // 3. T1
        if (entry.target1Price() != null && bar.high() != null
                && bar.high().compareTo(entry.target1Price()) >= 0) {
            return new ExitDecision(true, ExitReason.TARGET_1, entry.target1Price(), mfe, mae);
        }
        // 4. 時間出場
        if (bar.holdingDaysAtBarEnd() >= entry.maxHoldingDays()) {
            return new ExitDecision(true, ExitReason.TIME_EXIT, bar.close(), mfe, mae);
        }
        return ExitDecision.hold(mfe, mae);
    }

    private BigDecimal pct(BigDecimal price, BigDecimal entry) {
        if (price == null || entry == null || entry.signum() == 0) return null;
        return price.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
