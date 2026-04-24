package com.austin.trading.service;

import com.austin.trading.domain.enums.AllocationMode;
import com.austin.trading.dto.internal.CapitalAllocationInput;
import com.austin.trading.dto.internal.CapitalAllocationInput.AllocationIntent;
import com.austin.trading.dto.internal.CapitalAllocationResult;
import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.engine.CapitalAllocationEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v2.11 Capital Allocation 協調層。
 *
 * <p>職責：</p>
 * <ol>
 *     <li>從 {@link CapitalService} 拿 {@code availableCash / accountEquity}</li>
 *     <li>從 {@link PositionRepository} 加總 current portfolio exposure</li>
 *     <li>從 {@link ThemeExposureService} 算 theme exposure</li>
 *     <li>依 bucket / score / regime 決定 {@link AllocationMode}</li>
 *     <li>把 config 值轉成絕對小數塞進 {@link CapitalAllocationInput}</li>
 *     <li>呼叫 {@link CapitalAllocationEngine} 產出結果</li>
 * </ol>
 *
 * <p>不下單；只產 suggestion。</p>
 */
@Service
public class CapitalAllocationService {

    private static final Logger log = LoggerFactory.getLogger(CapitalAllocationService.class);

    /** 當 CapitalService 無資料時的 fallback 權益（保守預設）。 */
    public static final BigDecimal FALLBACK_EQUITY = new BigDecimal("50000");

    private final CapitalService capitalService;
    private final ScoreConfigService scoreConfigService;
    private final ThemeExposureService themeExposureService;
    private final PositionRepository positionRepository;
    private final CapitalAllocationEngine engine;

    public CapitalAllocationService(
            CapitalService capitalService,
            ScoreConfigService scoreConfigService,
            ThemeExposureService themeExposureService,
            PositionRepository positionRepository,
            CapitalAllocationEngine engine
    ) {
        this.capitalService = capitalService;
        this.scoreConfigService = scoreConfigService;
        this.themeExposureService = themeExposureService;
        this.positionRepository = positionRepository;
        this.engine = engine;
    }

    /** 新倉進場配置。 */
    public CapitalAllocationResult allocateForEntry(
            String symbol, String theme, String bucket, BigDecimal score,
            BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal stopLoss,
            BigDecimal targetPrice, String marketRegime
    ) {
        AllocationMode mode = resolveModeForEntry(bucket, score, marketRegime);
        return run(symbol, theme, bucket, score, entryPrice, currentPrice, stopLoss, targetPrice,
                marketRegime, mode, AllocationIntent.OPEN);
    }

    /** 既有持倉加碼配置。 */
    public CapitalAllocationResult allocateForAdd(
            String symbol, String theme, BigDecimal score,
            BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal stopLoss,
            BigDecimal targetPrice, String marketRegime, AllocationMode mode
    ) {
        AllocationMode m = mode == null ? AllocationMode.TRIAL : mode;
        return run(symbol, theme, "ADD", score, entryPrice, currentPrice, stopLoss, targetPrice,
                marketRegime, m, AllocationIntent.ADD);
    }

    /** 既有持倉減碼（輸出比例而非金額）。 */
    public CapitalAllocationResult allocateForReduce(
            String symbol, String theme, BigDecimal score,
            BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal stopLoss,
            BigDecimal targetPrice, String marketRegime, AllocationMode mode
    ) {
        AllocationMode m = mode == null ? AllocationMode.NORMAL : mode;
        return run(symbol, theme, "REDUCE", score, entryPrice, currentPrice, stopLoss, targetPrice,
                marketRegime, m, AllocationIntent.REDUCE);
    }

    /** 換股：對原倉輸出「減碼比例」；呼叫方另外為新倉呼叫 {@link #allocateForEntry}。 */
    public CapitalAllocationResult allocateForSwitchOldLeg(
            String symbol, String theme, BigDecimal score,
            BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal stopLoss,
            BigDecimal targetPrice, String marketRegime, AllocationMode mode
    ) {
        AllocationMode m = mode == null ? AllocationMode.NORMAL : mode;
        return run(symbol, theme, "SWITCH_OLD", score, entryPrice, currentPrice, stopLoss, targetPrice,
                marketRegime, m, AllocationIntent.SWITCH);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 共用組裝
    // ══════════════════════════════════════════════════════════════════════

    private CapitalAllocationResult run(
            String symbol, String theme, String bucket, BigDecimal score,
            BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal stopLoss,
            BigDecimal targetPrice, String marketRegime, AllocationMode mode,
            AllocationIntent intent
    ) {
        CapitalSummaryResponse summary = safeSummary();
        BigDecimal accountEquity = summary != null && summary.totalEquity() != null
                && summary.totalEquity().signum() > 0
                ? summary.totalEquity() : FALLBACK_EQUITY;
        BigDecimal availableCash = summary != null && summary.availableCash() != null
                ? summary.availableCash() : BigDecimal.ZERO;
        String equitySource = summary != null && summary.totalEquity() != null
                && summary.totalEquity().signum() > 0 ? "capital_ledger" : "fallback_default";

        // portfolio & theme exposures（金額）
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");
        BigDecimal portfolioExposure = openPositions.stream()
                .map(p -> p.getAvgCost() == null || p.getQty() == null
                        ? BigDecimal.ZERO
                        : p.getAvgCost().multiply(p.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // theme exposure：用 ThemeExposureService.compute() 回傳的是 %，先轉成金額
        BigDecimal themeExposureAmount = BigDecimal.ZERO;
        if (theme != null && !theme.isBlank()) {
            Map<String, BigDecimal> pctMap = themeExposureService.compute(openPositions);
            BigDecimal pct = pctMap.getOrDefault(theme.toUpperCase(Locale.ROOT), BigDecimal.ZERO);
            themeExposureAmount = accountEquity.multiply(pct)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        }

        // config（依 mode / regime 解析）
        BigDecimal riskPct = scoreConfigService.getDecimal(
                riskPctKey(mode), riskPctDefault(mode));
        BigDecimal singlePosLimit = scoreConfigService.getDecimal(
                maxPositionKey(mode), maxPositionDefault(mode));
        BigDecimal marketLimit = scoreConfigService.getDecimal(
                marketLimitKey(marketRegime), marketLimitDefault(marketRegime));
        BigDecimal themeLimit = scoreConfigService.getDecimal(
                "capital.theme_exposure_limit", new BigDecimal("0.40"));
        BigDecimal cashReservePct = scoreConfigService.getDecimal(
                "capital.cash_reserve_pct", new BigDecimal("0.10"));
        BigDecimal minTradeAmt = scoreConfigService.getDecimal(
                "capital.min_trade_amount", new BigDecimal("10000"));
        int lotSize = scoreConfigService.getInt("capital.round_lot_size", 1);
        BigDecimal reduceHintPct = scoreConfigService.getDecimal(
                "capital.reduce_hint_pct", new BigDecimal("0.40"));

        Map<String, Object> traceSeed = new LinkedHashMap<>();
        traceSeed.put("equitySource", equitySource);
        traceSeed.put("openPositionCount", openPositions.size());
        traceSeed.put("capital.reduce_hint_pct", reduceHintPct);

        CapitalAllocationInput in = new CapitalAllocationInput(
                symbol, theme, bucket, score,
                entryPrice, currentPrice, stopLoss, targetPrice,
                availableCash, accountEquity,
                portfolioExposure, themeExposureAmount,
                marketLimit, themeLimit, singlePosLimit,
                riskPct, cashReservePct, minTradeAmt, lotSize,
                marketRegime, mode, intent, traceSeed
        );
        CapitalAllocationResult result = engine.evaluate(in);
        log.info("[CapitalAllocation] {} intent={} action={} mode={} amount={} shares={} equity={}",
                symbol, intent, result.action(), mode,
                result.suggestedAmount(), result.suggestedShares(), accountEquity);
        return result;
    }

    private CapitalSummaryResponse safeSummary() {
        try {
            return capitalService.getSummary();
        } catch (Exception e) {
            log.warn("[CapitalAllocation] getSummary failed → fallback: {}", e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mode 解析
    // ══════════════════════════════════════════════════════════════════════

    public AllocationMode resolveModeForEntry(String bucket, BigDecimal score, String marketRegime) {
        String regime = marketRegime == null ? "" : marketRegime.toUpperCase(Locale.ROOT);
        if ("PANIC_VOLATILITY".equals(regime)) {
            return AllocationMode.TRIAL;  // 雖會被 marketLimit=0 擋掉，仍先給 mode
        }
        boolean bearLike = "WEAK_DOWNTREND".equals(regime) || "BEAR".equals(regime);
        if (bearLike) return AllocationMode.TRIAL;

        String b = bucket == null ? "" : bucket.trim().toUpperCase(Locale.ROOT);
        BigDecimal s = score == null ? BigDecimal.ZERO : score;
        boolean buyNow = "SELECT_BUY_NOW".equals(b);
        boolean convert = "CONVERT_BUY".equals(b);

        if (buyNow && s.compareTo(new BigDecimal("8.5")) >= 0) return AllocationMode.CORE;
        if (buyNow && s.compareTo(new BigDecimal("7.6")) >= 0) return AllocationMode.NORMAL;
        if (buyNow) return AllocationMode.TRIAL;
        if (convert) return AllocationMode.TRIAL;
        return AllocationMode.TRIAL;  // 其他 / 未知 → 最保守
    }

    // ══════════════════════════════════════════════════════════════════════
    // Config 解析
    // ══════════════════════════════════════════════════════════════════════

    private String riskPctKey(AllocationMode mode) {
        return "capital.risk_pct_per_trade." + mode.name().toLowerCase(Locale.ROOT);
    }

    private BigDecimal riskPctDefault(AllocationMode mode) {
        return switch (mode) {
            case TRIAL  -> new BigDecimal("0.003");
            case NORMAL -> new BigDecimal("0.006");
            case CORE   -> new BigDecimal("0.01");
        };
    }

    private String maxPositionKey(AllocationMode mode) {
        return "capital.max_position_pct." + mode.name().toLowerCase(Locale.ROOT);
    }

    private BigDecimal maxPositionDefault(AllocationMode mode) {
        return switch (mode) {
            case TRIAL  -> new BigDecimal("0.10");
            case NORMAL -> new BigDecimal("0.20");
            case CORE   -> new BigDecimal("0.30");
        };
    }

    private String marketLimitKey(String regime) {
        String r = regime == null ? "" : regime.toUpperCase(Locale.ROOT);
        return switch (r) {
            case "BULL_TREND" -> "capital.market_exposure_limit.bull";
            case "RANGE_CHOP" -> "capital.market_exposure_limit.range";
            case "WEAK_DOWNTREND", "BEAR" -> "capital.market_exposure_limit.bear";
            case "PANIC_VOLATILITY", "PANIC" -> "capital.market_exposure_limit.panic";
            default -> "capital.market_exposure_limit.range";  // unknown → 保守 range
        };
    }

    private BigDecimal marketLimitDefault(String regime) {
        String r = regime == null ? "" : regime.toUpperCase(Locale.ROOT);
        return switch (r) {
            case "BULL_TREND" -> new BigDecimal("0.80");
            case "WEAK_DOWNTREND", "BEAR" -> new BigDecimal("0.20");
            case "PANIC_VOLATILITY", "PANIC" -> BigDecimal.ZERO;
            default -> new BigDecimal("0.50");
        };
    }
}
