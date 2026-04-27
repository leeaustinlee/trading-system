package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.engine.exit.ExitRuleEvaluator;
import com.austin.trading.engine.exit.FixedRuleExitEvaluator;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.event.FinalDecisionPersistedEvent;
import com.austin.trading.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 Paper Trade 引擎:
 *
 * <ul>
 *   <li>{@link #onFinalDecisionPersisted} : final_decision ENTER 落地後自動開虛擬倉</li>
 *   <li>{@link #markToMarketAll} : 由 PaperTradeMtmJob 每日 13:35 呼叫,跑 ExitRuleEvaluator</li>
 *   <li>查詢方法供 Controller 用</li>
 * </ul>
 *
 * <p>不發任何通知。失敗時 graceful 寫 log,不向上拋出讓主交易流程被影響。</p>
 *
 * <p><b>Entry-time snapshot fields</b> (added by Subagent A):
 * 每筆 OPEN row 都會 capture 當下的 intended / simulated 進場價、entry_grade、
 * entry_rr_ratio、entry_regime 與 rich entry_payload_json,讓未來回測可以 100%
 * 重現決策當下系統看到的條件,而不必依賴 stale 的 daily bar。</p>
 */
@Service
public class PaperTradeService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeService.class);

    private static final BigDecimal DEFAULT_STOP_LOSS_PCT = new BigDecimal("0.05");   // -5%
    private static final BigDecimal DEFAULT_TARGET1_PCT   = new BigDecimal("0.08");   // +8%
    private static final BigDecimal DEFAULT_TARGET2_PCT   = new BigDecimal("0.15");   // +15%
    private static final int        DEFAULT_MAX_HOLD_DAYS = 5;

    /**
     * Buy-side slippage applied to live currentPrice when computing
     * {@code simulated_entry_price}. 0.001 = 0.1% (≈ 1 tick on a 100 NTD
     * stock — chosen because it's symbol-agnostic, easy to override per
     * symbol later, and conservatively above the typical TWSE 0.05 NTD
     * minimum tick on mid-priced names). Documented in CLAUDE.md.
     */
    private static final BigDecimal BUY_SLIPPAGE_FRACTION = new BigDecimal("0.001");

    private static final Pattern PRICE_NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private final PaperTradeRepository repository;
    private final TwseMisClient twseMisClient;
    private final FixedRuleExitEvaluator exitEvaluator;
    private final ObjectMapper objectMapper;

    /**
     * Static fallback gate driven by application.properties (legacy key).
     * 主要 runtime gate 走 {@link #isPaperModeEnabled()} → 透過 ScoreConfigService 讀
     * {@code trading.paper_mode.enabled}（DB-side flag,預設 TRUE）。
     */
    private final boolean staticEnabled;

    /** 用 ObjectProvider 解循環:ScoreConfigService 是後加的依賴,允許 null/缺席。 */
    private final ObjectProvider<ScoreConfigService> scoreConfigProvider;

    /** 同樣用 ObjectProvider 取得 MarketRegimeService,避免測試 / boot order 卡住。 */
    private final ObjectProvider<MarketRegimeService> marketRegimeProvider;

    public PaperTradeService(PaperTradeRepository repository,
                              TwseMisClient twseMisClient,
                              FixedRuleExitEvaluator exitEvaluator,
                              ObjectMapper objectMapper,
                              ObjectProvider<ScoreConfigService> scoreConfigProvider,
                              ObjectProvider<MarketRegimeService> marketRegimeProvider,
                              @Value("${trading.paper-trade.enabled:true}") boolean staticEnabled) {
        this.repository = repository;
        this.twseMisClient = twseMisClient;
        this.exitEvaluator = exitEvaluator;
        this.objectMapper = objectMapper;
        this.scoreConfigProvider = scoreConfigProvider;
        this.marketRegimeProvider = marketRegimeProvider;
        this.staticEnabled = staticEnabled;
    }

    /**
     * 是否啟用 paper trade。
     * <p>順序: ScoreConfigService("trading.paper_mode.enabled", true) AND application.properties flag。</p>
     */
    boolean isPaperModeEnabled() {
        if (!staticEnabled) return false;
        ScoreConfigService cfg = scoreConfigProvider != null ? scoreConfigProvider.getIfAvailable() : null;
        if (cfg == null) return true; // 沒有 DB config 時 default true
        return cfg.getBoolean("trading.paper_mode.enabled", true);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 開倉:訂閱 FinalDecisionPersisted 事件
    // ══════════════════════════════════════════════════════════════════════

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFinalDecisionPersisted(FinalDecisionPersistedEvent event) {
        if (!isPaperModeEnabled()) {
            log.debug("[PaperTrade] paper_mode disabled, skip event finalDecisionId={}",
                    event != null ? event.finalDecisionId() : null);
            return;
        }
        if (event == null || !"ENTER".equalsIgnoreCase(event.decisionCode())) return;
        List<FinalDecisionSelectedStockResponse> picks = event.selectedStocks();
        if (picks == null || picks.isEmpty()) {
            log.debug("[PaperTrade] ENTER decision but selectedStocks empty, skip finalDecisionId={}",
                    event.finalDecisionId());
            return;
        }

        for (FinalDecisionSelectedStockResponse pick : picks) {
            try {
                openOne(event, pick);
            } catch (Exception e) {
                log.warn("[PaperTrade] open failed symbol={} reason={}", pick.stockCode(), e.getMessage(), e);
            }
        }
    }

    private void openOne(FinalDecisionPersistedEvent event, FinalDecisionSelectedStockResponse pick) {
        String symbol = pick.stockCode();
        if (symbol == null || symbol.isBlank()) return;

        // Idempotency:同一交易日同 symbol 已開過就略過
        List<PaperTradeEntity> existing = repository.findByEntryDateAndSymbol(event.tradingDate(), symbol);
        boolean alreadyOpen = existing.stream().anyMatch(p -> "OPEN".equals(p.getStatus()));
        if (alreadyOpen) {
            log.debug("[PaperTrade] already OPEN entry={} symbol={}, skip", event.tradingDate(), symbol);
            return;
        }

        BigDecimal intendedPrice = resolveEntryPrice(symbol, pick.entryPriceZone());
        if (intendedPrice == null || intendedPrice.signum() <= 0) {
            log.warn("[PaperTrade] cannot resolve entry price symbol={} zone={}, skip",
                    symbol, pick.entryPriceZone());
            return;
        }

        BigDecimal stop  = bd(pick.stopLossPrice());
        BigDecimal tp1   = bd(pick.takeProfit1());
        BigDecimal tp2   = bd(pick.takeProfit2());
        if (stop == null) stop = intendedPrice.multiply(BigDecimal.ONE.subtract(DEFAULT_STOP_LOSS_PCT))
                .setScale(4, RoundingMode.HALF_UP);
        if (tp1  == null) tp1  = intendedPrice.multiply(BigDecimal.ONE.add(DEFAULT_TARGET1_PCT))
                .setScale(4, RoundingMode.HALF_UP);
        if (tp2  == null) tp2  = intendedPrice.multiply(BigDecimal.ONE.add(DEFAULT_TARGET2_PCT))
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal simulatedPrice = simulateBuyFill(symbol, intendedPrice);
        String regime = currentRegimeType();
        BigDecimal rr = pick.riskRewardRatio() != null
                ? BigDecimal.valueOf(pick.riskRewardRatio()).setScale(3, RoundingMode.HALF_UP)
                : null;
        String grade = deriveEntryGrade(pick);

        PaperTradeEntity e = new PaperTradeEntity();
        e.setTradeId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        e.setEntryDate(event.tradingDate());
        e.setEntryTime(LocalTime.now());
        e.setSymbol(symbol);
        e.setStockName(pick.stockName());
        e.setEntryPrice(intendedPrice);
        e.setIntendedEntryPrice(intendedPrice);
        e.setSimulatedEntryPrice(simulatedPrice);
        e.setStopLossPrice(stop);
        e.setTarget1Price(tp1);
        e.setTarget2Price(tp2);
        e.setMaxHoldingDays(DEFAULT_MAX_HOLD_DAYS);
        e.setSource(deriveSource(event.aiStatus()));
        e.setStrategyType(pick.strategyType() != null ? pick.strategyType() : event.strategyType());
        e.setFinalDecisionId(event.finalDecisionId());
        e.setAiTaskId(event.aiTaskId());
        e.setEntryGrade(grade);
        e.setEntryRrRatio(rr);
        e.setEntryRegime(regime);
        e.setStatus("OPEN");
        e.setPayloadJson(buildEntryPayload(event, pick, intendedPrice));
        e.setEntryPayloadJson(buildRichEntryPayload(event, pick, intendedPrice, simulatedPrice, regime, grade, rr));
        repository.save(e);
        log.info("[PaperTrade] OPEN id={} symbol={} intended={} simulated={} stop={} tp1={} tp2={} regime={} grade={} rr={}",
                e.getId(), symbol, intendedPrice, simulatedPrice, stop, tp1, tp2, regime, grade, rr);
    }

    // ══════════════════════════════════════════════════════════════════════
    // P0.2 公開 API:由 FinalDecisionService 或測試直接呼叫
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 為一檔股票直接寫入一筆 OPEN 虛擬倉。
     *
     * <p>由 ENTER 流程在 {@code trading.paper_mode.enabled=true}(預設 TRUE)時呼叫,
     * 不依賴 FinalDecisionPersistedEvent 訊號,讓上游可在任何 ENTER 路徑強制紀錄。</p>
     *
     * <p>Idempotency:同一交易日同 symbol 已存在 OPEN 狀態的 paper trade 時直接略過(不丟錯)。</p>
     *
     * @param symbol     股票代碼(必填)
     * @param entryPrice 進場價(必填,>0)。視為 intended_entry_price。
     * @param stopLoss   停損價(可 null,缺則用 -5%)
     * @param tp1        第一停利(可 null,缺則用 +8%)
     * @param tp2        第二停利(可 null,缺則用 +15%)
     * @param qty        計畫持股數(可 null,只是當 metadata 用)
     * @param reason     進場原因/備註(會寫入 payload_json)
     * @return 寫入的 entity;若被 idempotency 跳過或 paper_mode 關閉則回 null
     */
    @Transactional
    public PaperTradeEntity recordEntry(String symbol,
                                        BigDecimal entryPrice,
                                        BigDecimal stopLoss,
                                        BigDecimal tp1,
                                        BigDecimal tp2,
                                        Integer qty,
                                        String reason) {
        if (!isPaperModeEnabled()) {
            log.debug("[PaperTrade] recordEntry paper_mode disabled symbol={}", symbol);
            return null;
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (entryPrice == null || entryPrice.signum() <= 0) {
            throw new IllegalArgumentException("entryPrice must be > 0");
        }

        LocalDate today = LocalDate.now();

        // Idempotency:同 entry_date + symbol 已 OPEN 直接 return
        List<PaperTradeEntity> existing = repository.findByEntryDateAndSymbol(today, symbol);
        for (PaperTradeEntity p : existing) {
            if ("OPEN".equals(p.getStatus())) {
                log.info("[PaperTrade] recordEntry idempotent skip symbol={} entry_date={} existingId={}",
                        symbol, today, p.getId());
                return p;
            }
        }

        BigDecimal intended = entryPrice.setScale(4, RoundingMode.HALF_UP);

        BigDecimal stop = stopLoss != null ? stopLoss
                : intended.multiply(BigDecimal.ONE.subtract(DEFAULT_STOP_LOSS_PCT))
                        .setScale(4, RoundingMode.HALF_UP);
        BigDecimal t1 = tp1 != null ? tp1
                : intended.multiply(BigDecimal.ONE.add(DEFAULT_TARGET1_PCT))
                        .setScale(4, RoundingMode.HALF_UP);
        BigDecimal t2 = tp2 != null ? tp2
                : intended.multiply(BigDecimal.ONE.add(DEFAULT_TARGET2_PCT))
                        .setScale(4, RoundingMode.HALF_UP);

        BigDecimal simulated = simulateBuyFill(symbol, intended);
        String regime = currentRegimeType();
        BigDecimal rr = computeRrRatio(intended, stop, t1);
        String grade = "B_TRIAL"; // manual entries default to TRIAL grade

        PaperTradeEntity e = new PaperTradeEntity();
        e.setTradeId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        e.setEntryDate(today);
        e.setEntryTime(LocalTime.now());
        e.setSymbol(symbol);
        e.setEntryPrice(intended);
        e.setIntendedEntryPrice(intended);
        e.setSimulatedEntryPrice(simulated);
        e.setPositionShares(qty);
        if (qty != null && qty > 0) {
            e.setPositionAmount(intended.multiply(BigDecimal.valueOf(qty))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        e.setStopLossPrice(stop);
        e.setTarget1Price(t1);
        e.setTarget2Price(t2);
        e.setMaxHoldingDays(DEFAULT_MAX_HOLD_DAYS);
        e.setSource("MANUAL");
        e.setStrategyType("SETUP");
        e.setEntryGrade(grade);
        e.setEntryRrRatio(rr);
        e.setEntryRegime(regime);
        e.setStatus("OPEN");
        e.setPayloadJson(buildManualEntryPayload(symbol, intended, stop, t1, t2, qty, reason));
        e.setEntryPayloadJson(buildRichManualEntryPayload(
                symbol, intended, simulated, stop, t1, t2, qty, reason, regime, grade, rr));
        repository.save(e);
        log.info("[PaperTrade] recordEntry OPEN id={} symbol={} intended={} simulated={} stop={} tp1={} tp2={} qty={} regime={} grade={}",
                e.getId(), symbol, intended, simulated, stop, t1, t2, qty, regime, grade);
        return e;
    }

    private String buildManualEntryPayload(String symbol, BigDecimal entryPrice,
                                            BigDecimal stop, BigDecimal tp1, BigDecimal tp2,
                                            Integer qty, String reason) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("source", "recordEntry");
            n.put("symbol", symbol);
            n.put("entryPrice", entryPrice.toPlainString());
            if (stop != null) n.put("stopLoss", stop.toPlainString());
            if (tp1  != null) n.put("tp1", tp1.toPlainString());
            if (tp2  != null) n.put("tp2", tp2.toPlainString());
            if (qty  != null) n.put("qty", qty);
            if (reason != null) n.put("reason", reason);
            return objectMapper.writeValueAsString(n);
        } catch (Exception ex) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Entry-time snapshot helpers (Subagent A)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Simulate a buy-side fill: live currentPrice * (1 + slippage). When
     * the live quote is missing or unavailable, return the intended price
     * unchanged (so 回測 sees no slippage rather than a misleading number).
     */
    BigDecimal simulateBuyFill(String symbol, BigDecimal intended) {
        if (intended == null) return null;
        BigDecimal live = fetchLivePrice(symbol);
        if (live == null || live.signum() <= 0) {
            return intended.setScale(4, RoundingMode.HALF_UP);
        }
        return live.multiply(BigDecimal.ONE.add(BUY_SLIPPAGE_FRACTION))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** Read current regime from MarketRegimeService — null if unavailable. */
    String currentRegimeType() {
        MarketRegimeService svc = marketRegimeProvider != null ? marketRegimeProvider.getIfAvailable() : null;
        if (svc == null) return null;
        try {
            Optional<MarketRegimeDecision> latest = svc.getLatestForToday();
            if (latest.isEmpty()) latest = svc.getLatest();
            return latest.map(MarketRegimeDecision::regimeType).orElse(null);
        } catch (Exception ex) {
            log.debug("[PaperTrade] regime lookup failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Map FinalDecision pick → entry grade. v1 heuristic:
     * <ul>
     *   <li>{@code MOMENTUM_CHASE} + momentumScore >= 8 → A_PLUS</li>
     *   <li>otherwise SETUP                               → A_NORMAL</li>
     *   <li>positionMultiplier &lt; 1                     → B_TRIAL</li>
     * </ul>
     * Conservative default = A_NORMAL so a missing momentum score never
     * promotes a row.
     */
    static String deriveEntryGrade(FinalDecisionSelectedStockResponse pick) {
        if (pick == null) return null;
        Double mult = pick.positionMultiplier();
        if (mult != null && mult < 1.0) return "B_TRIAL";
        if ("MOMENTUM_CHASE".equalsIgnoreCase(pick.strategyType())) {
            Double m = pick.momentumScore();
            if (m != null && m >= 8.0) return "A_PLUS";
        }
        return "A_NORMAL";
    }

    /** Fallback RR computation: (tp1 − entry) / (entry − stop). */
    static BigDecimal computeRrRatio(BigDecimal entry, BigDecimal stop, BigDecimal tp1) {
        if (entry == null || stop == null || tp1 == null) return null;
        BigDecimal risk   = entry.subtract(stop);
        BigDecimal reward = tp1.subtract(entry);
        if (risk.signum() <= 0) return null;
        return reward.divide(risk, 3, RoundingMode.HALF_UP);
    }

    private String buildRichEntryPayload(FinalDecisionPersistedEvent ev,
                                          FinalDecisionSelectedStockResponse p,
                                          BigDecimal intended,
                                          BigDecimal simulated,
                                          String regime,
                                          String grade,
                                          BigDecimal rr) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("source", "onFinalDecisionPersisted");
            if (ev.finalDecisionId() != null) root.put("decisionId", ev.finalDecisionId());
            if (ev.aiTaskId() != null) root.put("aiTaskId", ev.aiTaskId());
            if (ev.aiStatus() != null) root.put("aiStatus", ev.aiStatus());
            if (ev.sourceTaskType() != null) root.put("sourceTaskType", ev.sourceTaskType());
            if (ev.tradingDate() != null) root.put("tradingDate", ev.tradingDate().toString());

            ObjectNode cand = root.putObject("candidate");
            cand.put("symbol", p.stockCode());
            if (p.stockName() != null) cand.put("stockName", p.stockName());
            if (p.entryType() != null) cand.put("entryType", p.entryType());
            if (p.entryPriceZone() != null) cand.put("entryPriceZone", p.entryPriceZone());
            if (p.stopLossPrice() != null) cand.put("stopLossPrice", p.stopLossPrice());
            if (p.takeProfit1() != null) cand.put("takeProfit1", p.takeProfit1());
            if (p.takeProfit2() != null) cand.put("takeProfit2", p.takeProfit2());
            if (p.suggestedPositionSize() != null) cand.put("suggestedPositionSize", p.suggestedPositionSize());
            if (p.positionMultiplier() != null) cand.put("positionMultiplier", p.positionMultiplier());
            if (p.strategyType() != null) cand.put("strategyType", p.strategyType());
            if (p.momentumScore() != null) cand.put("momentumScore", p.momentumScore());
            if (p.rationale() != null) cand.put("rationale", p.rationale());
            if (p.riskRewardRatio() != null) cand.put("riskRewardRatio", p.riskRewardRatio());

            ObjectNode entry = root.putObject("entry");
            entry.put("intendedPrice", intended.toPlainString());
            if (simulated != null) entry.put("simulatedPrice", simulated.toPlainString());
            if (grade != null) entry.put("grade", grade);
            if (rr != null) entry.put("rrRatio", rr.toPlainString());
            entry.put("slippageFraction", BUY_SLIPPAGE_FRACTION.toPlainString());

            if (regime != null) {
                ObjectNode r = root.putObject("regime");
                r.put("regimeType", regime);
            }

            // Theme exposure / capital alloc / scoring trace are not directly
            // available in the event payload — leave hooks for future enrichment.
            root.putObject("themeExposure"); // empty placeholder
            root.putObject("capitalAlloc");  // empty placeholder
            root.putObject("scoringTrace");  // empty placeholder
            ObjectNode aiOverride = root.putObject("aiWeightOverride");
            if (ev.aiStatus() != null) aiOverride.put("aiStatus", ev.aiStatus());

            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            log.debug("[PaperTrade] buildRichEntryPayload failed: {}", ex.getMessage());
            return null;
        }
    }

    private String buildRichManualEntryPayload(String symbol,
                                                BigDecimal intended,
                                                BigDecimal simulated,
                                                BigDecimal stop,
                                                BigDecimal tp1,
                                                BigDecimal tp2,
                                                Integer qty,
                                                String reason,
                                                String regime,
                                                String grade,
                                                BigDecimal rr) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("source", "recordEntry");

            ObjectNode cand = root.putObject("candidate");
            cand.put("symbol", symbol);
            if (stop != null) cand.put("stopLossPrice", stop.toPlainString());
            if (tp1  != null) cand.put("takeProfit1", tp1.toPlainString());
            if (tp2  != null) cand.put("takeProfit2", tp2.toPlainString());
            if (qty  != null) cand.put("plannedQty", qty);
            if (reason != null) cand.put("rationale", reason);

            ObjectNode entry = root.putObject("entry");
            entry.put("intendedPrice", intended.toPlainString());
            if (simulated != null) entry.put("simulatedPrice", simulated.toPlainString());
            if (grade != null) entry.put("grade", grade);
            if (rr != null) entry.put("rrRatio", rr.toPlainString());
            entry.put("slippageFraction", BUY_SLIPPAGE_FRACTION.toPlainString());

            if (regime != null) {
                ObjectNode r = root.putObject("regime");
                r.put("regimeType", regime);
            }
            root.putObject("themeExposure");
            root.putObject("capitalAlloc");
            root.putObject("scoringTrace");
            root.putObject("aiWeightOverride");

            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            log.debug("[PaperTrade] buildRichManualEntryPayload failed: {}", ex.getMessage());
            return null;
        }
    }

    private BigDecimal resolveEntryPrice(String symbol, String entryPriceZone) {
        BigDecimal parsed = parsePriceZone(entryPriceZone);
        if (parsed != null) return parsed;
        return fetchLivePrice(symbol);
    }

    /**
     * 解析 entryPriceZone 字串(常見格式:"100~105", "100-105", "100", "100元至105元")。
     * 取出數字後若兩個則平均,只有一個就回該值。
     */
    static BigDecimal parsePriceZone(String zone) {
        if (zone == null || zone.isBlank()) return null;
        Matcher m = PRICE_NUM.matcher(zone);
        BigDecimal first = null, second = null;
        if (m.find()) first = new BigDecimal(m.group(1));
        if (m.find()) second = new BigDecimal(m.group(1));
        if (first == null) return null;
        if (second == null) return first.setScale(4, RoundingMode.HALF_UP);
        return first.add(second).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal fetchLivePrice(String symbol) {
        Optional<StockQuote> q = twseMisClient.getTseQuote(symbol);
        if (q.isEmpty()) q = twseMisClient.getOtcQuote(symbol);
        if (q.isEmpty()) return null;
        StockQuote sq = q.get();
        Double price = sq.currentPrice() != null ? sq.currentPrice() : sq.prevClose();
        return price == null ? null : BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP);
    }

    private String deriveSource(String aiStatus) {
        if (aiStatus == null) return "HYBRID";
        return switch (aiStatus.toUpperCase()) {
            case "FULL_AI_READY" -> "HYBRID";
            case "PARTIAL_AI_READY" -> "CLAUDE";
            default -> "JAVA";
        };
    }

    private String buildEntryPayload(FinalDecisionPersistedEvent ev, FinalDecisionSelectedStockResponse p,
                                      BigDecimal entryPrice) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("finalDecisionId", ev.finalDecisionId());
            n.put("aiStatus", ev.aiStatus());
            n.put("sourceTaskType", ev.sourceTaskType());
            n.put("entryPriceZone", p.entryPriceZone());
            n.put("resolvedEntryPrice", entryPrice.toPlainString());
            if (p.riskRewardRatio() != null) n.put("riskRewardRatio", p.riskRewardRatio());
            if (p.suggestedPositionSize() != null) n.put("suggestedPositionSize", p.suggestedPositionSize());
            if (p.momentumScore() != null) n.put("momentumScore", p.momentumScore());
            if (p.rationale() != null) n.put("rationale", p.rationale());
            return objectMapper.writeValueAsString(n);
        } catch (Exception ex) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mark-to-market(由 PaperTradeMtmJob 每日 13:35 呼叫)
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public MtmSummary markToMarketAll(LocalDate barDate) {
        if (!isPaperModeEnabled()) return new MtmSummary(0, 0, 0);
        List<PaperTradeEntity> open = repository.findByStatusOrderByEntryDateAscIdAsc("OPEN");
        int updated = 0, closed = 0, errors = 0;
        for (PaperTradeEntity p : open) {
            try {
                if (markToMarketOne(p, barDate)) closed++;
                updated++;
            } catch (Exception e) {
                errors++;
                log.warn("[PaperTrade] MTM failed id={} symbol={} : {}", p.getId(), p.getSymbol(), e.getMessage());
            }
        }
        log.info("[PaperTrade] MTM done date={} total={} closed={} errors={}", barDate, updated, closed, errors);
        return new MtmSummary(updated, closed, errors);
    }

    /**
     * @return true 表示這筆已平倉
     */
    boolean markToMarketOne(PaperTradeEntity p, LocalDate barDate) {
        // 進場日當天不平倉
        if (!barDate.isAfter(p.getEntryDate())) {
            log.debug("[PaperTrade] skip MTM same/before entry date id={} entry={} bar={}",
                    p.getId(), p.getEntryDate(), barDate);
            return false;
        }

        Optional<StockQuote> q = twseMisClient.getTseQuote(p.getSymbol());
        if (q.isEmpty()) q = twseMisClient.getOtcQuote(p.getSymbol());
        if (q.isEmpty() || !q.get().available()) {
            log.warn("[PaperTrade] no quote for MTM id={} symbol={}", p.getId(), p.getSymbol());
            return false;
        }

        StockQuote sq = q.get();
        BigDecimal high  = nullSafe(sq.dayHigh());
        BigDecimal low   = nullSafe(sq.dayLow());
        BigDecimal close = nullSafe(sq.currentPrice() != null ? sq.currentPrice() : sq.prevClose());
        if (high == null || low == null || close == null) {
            log.warn("[PaperTrade] incomplete bar id={} symbol={} high={} low={} close={}",
                    p.getId(), p.getSymbol(), high, low, close);
            return false;
        }

        int holdingDays = (int) ChronoUnit.DAYS.between(p.getEntryDate(), barDate);

        ExitRuleEvaluator.EntrySnapshot snap = new ExitRuleEvaluator.EntrySnapshot(
                p.getEntryDate(), p.getEntryPrice(),
                p.getStopLossPrice(), p.getTarget1Price(), p.getTarget2Price(),
                p.getMaxHoldingDays() != null ? p.getMaxHoldingDays() : DEFAULT_MAX_HOLD_DAYS);
        ExitRuleEvaluator.DailyBar bar = new ExitRuleEvaluator.DailyBar(barDate, high, low, close, holdingDays);
        ExitRuleEvaluator.ExitDecision ed = exitEvaluator.evaluate(snap, bar);

        // 累積 mfe / mae(取最大值)
        if (ed.mfePct() != null && (p.getMfePct() == null || ed.mfePct().compareTo(p.getMfePct()) > 0)) {
            p.setMfePct(ed.mfePct());
        }
        if (ed.maePct() != null && (p.getMaePct() == null || ed.maePct().compareTo(p.getMaePct()) < 0)) {
            p.setMaePct(ed.maePct());
        }

        if (ed.shouldExit()) {
            p.setStatus("CLOSED");
            p.setExitDate(barDate);
            p.setExitTime(LocalTime.of(13, 30));
            p.setExitPrice(ed.exitPrice());
            p.setExitReason(ed.reason().name());
            p.setHoldingDays(holdingDays);
            p.setPnlPct(pct(ed.exitPrice(), p.getEntryPrice()));
            if (p.getPositionAmount() != null && p.getPnlPct() != null) {
                p.setPnlAmount(p.getPositionAmount()
                        .multiply(p.getPnlPct().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))
                        .setScale(2, RoundingMode.HALF_UP));
            }
            repository.save(p);
            log.info("[PaperTrade] CLOSE id={} symbol={} reason={} exit={} pnl%={}",
                    p.getId(), p.getSymbol(), ed.reason(), ed.exitPrice(), p.getPnlPct());
            return true;
        }

        repository.save(p);
        return false;
    }

    private BigDecimal nullSafe(Double d) {
        return d == null ? null : BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal price, BigDecimal entry) {
        if (price == null || entry == null || entry.signum() == 0) return null;
        return price.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(Double d) {
        return d == null ? null : BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 查詢(Controller 用)
    // ══════════════════════════════════════════════════════════════════════

    public List<PaperTradeEntity> listOpen() {
        return repository.findByStatusOrderByEntryDateAscIdAsc("OPEN");
    }

    public List<PaperTradeEntity> listClosed(LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return repository.findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc("CLOSED", from, to);
        }
        if (from != null) {
            return repository.findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("CLOSED", from);
        }
        return repository.findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
                "CLOSED", LocalDate.now().minusDays(90), LocalDate.now());
    }

    public List<PaperTradeEntity> listAllForKpi(LocalDate from, LocalDate to) {
        List<PaperTradeEntity> all = new ArrayList<>(listClosed(from, to));
        if (from == null && to == null) {
            // 還算進 open(供 dashboard 顯示中)
        }
        return all;
    }

    public record MtmSummary(int total, int closed, int errors) {}
}
