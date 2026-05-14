package com.austin.trading.service;

import com.austin.trading.dto.response.RiskRewardShadowGateResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class RiskRewardShadowGateService {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String DATA_GAP = "DATA_GAP";

    private static final BigDecimal DEFAULT_MIN_RR = new BigDecimal("1.8");
    private static final BigDecimal MOMENTUM_MIN_RR = new BigDecimal("2.0");

    public RiskRewardShadowGateResult evaluate(PriceSnapshot snapshot) {
        BigDecimal minRequired = minRequiredRr(snapshot.strategyType());
        BigDecimal target = firstPositive(snapshot.target1(), snapshot.target2());
        if (snapshot.entryPrice() == null || snapshot.stopPrice() == null || target == null) {
            return result(null, minRequired, DATA_GAP,
                    "DATA_GAP: missing entry/stop/target price", snapshot);
        }
        if (snapshot.entryPrice().signum() <= 0 || snapshot.stopPrice().signum() <= 0 || target.signum() <= 0) {
            return result(null, minRequired, DATA_GAP,
                    "DATA_GAP: non-positive entry/stop/target price", snapshot);
        }

        BigDecimal risk = snapshot.entryPrice().subtract(snapshot.stopPrice());
        BigDecimal reward = target.subtract(snapshot.entryPrice());
        if (risk.signum() <= 0 || reward.signum() <= 0) {
            return result(null, minRequired, FAIL,
                    "INVALID_PRICE_STRUCTURE: stop must be below entry and target above entry", snapshot);
        }

        BigDecimal rr = reward.divide(risk, 4, RoundingMode.HALF_UP);
        String status = rr.compareTo(minRequired) >= 0 ? PASS : FAIL;
        String reason = status.equals(PASS)
                ? "RR >= minRequiredRr"
                : "RR below minRequiredRr";
        return result(rr, minRequired, status, reason, snapshot);
    }

    public BigDecimal minRequiredRr(String strategyType) {
        // TODO: 後續若要調參，改接 ScoreConfigService / application config；目前維持 shadow-only 常數。
        if (strategyType != null && strategyType.toUpperCase().contains("MOMENTUM")) {
            return MOMENTUM_MIN_RR;
        }
        return DEFAULT_MIN_RR;
    }

    private BigDecimal firstPositive(BigDecimal target1, BigDecimal target2) {
        if (target1 != null && target1.signum() > 0) return target1;
        if (target2 != null && target2.signum() > 0) return target2;
        return null;
    }

    private RiskRewardShadowGateResult result(BigDecimal rr,
                                              BigDecimal minRequired,
                                              String status,
                                              String reason,
                                              PriceSnapshot snapshot) {
        return new RiskRewardShadowGateResult(
                rr,
                minRequired,
                status,
                reason,
                snapshot.entryPrice(),
                snapshot.stopPrice(),
                snapshot.target1(),
                snapshot.target2()
        );
    }

    public record PriceSnapshot(
            String symbol,
            String strategyType,
            BigDecimal entryPrice,
            BigDecimal stopPrice,
            BigDecimal target1,
            BigDecimal target2
    ) {
    }
}
