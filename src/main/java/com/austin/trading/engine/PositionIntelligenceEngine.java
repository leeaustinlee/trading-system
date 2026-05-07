package com.austin.trading.engine;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.PositionRiskLevel;
import com.austin.trading.domain.enums.PositionStrength;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import com.austin.trading.entity.PositionEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class PositionIntelligenceEngine {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal STRONG_RETURN = new BigDecimal("0.08");
    private static final BigDecimal WEAK_RETURN = new BigDecimal("-0.03");
    private static final BigDecimal RAISE_STOP_RATIO = new BigDecimal("0.94");
    private static final BigDecimal DEFAULT_STOP_RATIO = new BigDecimal("0.93");
    private static final BigDecimal DEFAULT_TP_RATIO = new BigDecimal("1.12");

    public PositionIntelligenceResultDto evaluatePosition(PositionEntity position) {
        BigDecimal avgCost = positive(position.getAvgCost()) ? position.getAvgCost() : ONE;
        BigDecimal referencePrice = resolveReferencePrice(position, avgCost);
        BigDecimal returnPct = referencePrice.subtract(avgCost).divide(avgCost, 6, RoundingMode.HALF_UP);
        BigDecimal effectiveStop = max(position.getStopLossPrice(), position.getTrailingStopPrice());
        boolean stopBroken = positive(effectiveStop) && referencePrice.compareTo(effectiveStop) < 0;
        List<String> reasons = new ArrayList<>();

        PositionStrength strength;
        if (stopBroken || returnPct.compareTo(WEAK_RETURN) <= 0 || isWeakStatus(position.getReviewStatus())) {
            strength = PositionStrength.WEAK;
        } else if (returnPct.compareTo(STRONG_RETURN) >= 0 || isStrongStatus(position.getReviewStatus())) {
            strength = PositionStrength.STRONG;
        } else {
            strength = PositionStrength.NEUTRAL;
        }

        PositionRiskLevel risk;
        if (stopBroken || returnPct.compareTo(new BigDecimal("-0.05")) <= 0) {
            risk = PositionRiskLevel.HIGH;
        } else if (returnPct.compareTo(new BigDecimal("0.18")) >= 0) {
            risk = PositionRiskLevel.MEDIUM;
            reasons.add("已有較大漲幅，需注意獲利回吐");
        } else {
            risk = PositionRiskLevel.LOW;
        }

        HoldDecision holdDecision;
        if (strength == PositionStrength.WEAK || risk == PositionRiskLevel.HIGH) {
            holdDecision = HoldDecision.EXIT;
        } else if (strength == PositionStrength.STRONG && risk == PositionRiskLevel.LOW) {
            holdDecision = HoldDecision.HIGH_HOLD;
        } else if (strength == PositionStrength.STRONG) {
            holdDecision = HoldDecision.HOLD;
        } else if (risk == PositionRiskLevel.MEDIUM) {
            holdDecision = HoldDecision.REDUCE;
        } else {
            holdDecision = HoldDecision.HOLD;
        }

        BigDecimal suggestedStop = suggestedStop(referencePrice, avgCost, effectiveStop, holdDecision);
        BigDecimal suggestedTakeProfit = positive(position.getTakeProfit2())
                ? position.getTakeProfit2()
                : avgCost.multiply(DEFAULT_TP_RATIO).setScale(4, RoundingMode.HALF_UP);

        if (!positive(position.getTrailingStopPrice()) && !positive(position.getStopLossPrice())) {
            reasons.add("缺少 MA/量能/即時價完整資料，先以成本、停損與 reviewStatus 做保守健檢");
        }
        reasons.add("strength=" + strength + ", risk=" + risk + ", holdDecision=" + holdDecision);
        if (stopBroken) reasons.add("參考價格已跌破既有 stop/trailing stop");

        return new PositionIntelligenceResultDto(
                position.getSymbol(),
                position.getStockName(),
                strength,
                risk,
                holdDecision,
                suggestedStop,
                suggestedTakeProfit,
                null,
                String.join("；", reasons)
        );
    }

    private BigDecimal suggestedStop(BigDecimal referencePrice, BigDecimal avgCost, BigDecimal existingStop, HoldDecision decision) {
        BigDecimal candidate;
        if (decision == HoldDecision.HIGH_HOLD || decision == HoldDecision.HOLD) {
            candidate = referencePrice.multiply(RAISE_STOP_RATIO);
        } else {
            candidate = avgCost.multiply(DEFAULT_STOP_RATIO);
        }
        candidate = candidate.setScale(4, RoundingMode.HALF_UP);
        if (positive(existingStop) && candidate.compareTo(existingStop) < 0) {
            return existingStop.setScale(4, RoundingMode.HALF_UP);
        }
        return candidate;
    }

    private BigDecimal resolveReferencePrice(PositionEntity position, BigDecimal avgCost) {
        if (positive(position.getClosePrice())) return position.getClosePrice();
        if (positive(position.getTakeProfit1())) return position.getTakeProfit1();
        return avgCost;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(ZERO) > 0;
    }

    private BigDecimal max(BigDecimal a, BigDecimal b) {
        if (!positive(a)) return b;
        if (!positive(b)) return a;
        return a.max(b);
    }

    private boolean isStrongStatus(String status) {
        if (status == null) return false;
        return status.equalsIgnoreCase("STRONG") || status.equalsIgnoreCase("TRAIL_UP") || status.equalsIgnoreCase("HOLD_STRONG");
    }

    private boolean isWeakStatus(String status) {
        if (status == null) return false;
        return status.equalsIgnoreCase("WEAK") || status.equalsIgnoreCase("EXIT") || status.equalsIgnoreCase("DATA_BLOCKED") || status.equalsIgnoreCase("QUOTE_STALE");
    }
}
