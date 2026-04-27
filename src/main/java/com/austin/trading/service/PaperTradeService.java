package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
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
 */
@Service
public class PaperTradeService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeService.class);

    private static final BigDecimal DEFAULT_STOP_LOSS_PCT = new BigDecimal("0.05");   // -5%
    private static final BigDecimal DEFAULT_TARGET1_PCT   = new BigDecimal("0.08");   // +8%
    private static final BigDecimal DEFAULT_TARGET2_PCT   = new BigDecimal("0.15");   // +15%
    private static final int        DEFAULT_MAX_HOLD_DAYS = 5;

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

    public PaperTradeService(PaperTradeRepository repository,
                              TwseMisClient twseMisClient,
                              FixedRuleExitEvaluator exitEvaluator,
                              ObjectMapper objectMapper,
                              ObjectProvider<ScoreConfigService> scoreConfigProvider,
                              @Value("${trading.paper-trade.enabled:true}") boolean staticEnabled) {
        this.repository = repository;
        this.twseMisClient = twseMisClient;
        this.exitEvaluator = exitEvaluator;
        this.objectMapper = objectMapper;
        this.scoreConfigProvider = scoreConfigProvider;
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

        BigDecimal entryPrice = resolveEntryPrice(symbol, pick.entryPriceZone());
        if (entryPrice == null || entryPrice.signum() <= 0) {
            log.warn("[PaperTrade] cannot resolve entry price symbol={} zone={}, skip",
                    symbol, pick.entryPriceZone());
            return;
        }

        BigDecimal stop  = bd(pick.stopLossPrice());
        BigDecimal tp1   = bd(pick.takeProfit1());
        BigDecimal tp2   = bd(pick.takeProfit2());
        if (stop == null) stop = entryPrice.multiply(BigDecimal.ONE.subtract(DEFAULT_STOP_LOSS_PCT))
                .setScale(4, RoundingMode.HALF_UP);
        if (tp1  == null) tp1  = entryPrice.multiply(BigDecimal.ONE.add(DEFAULT_TARGET1_PCT))
                .setScale(4, RoundingMode.HALF_UP);
        if (tp2  == null) tp2  = entryPrice.multiply(BigDecimal.ONE.add(DEFAULT_TARGET2_PCT))
                .setScale(4, RoundingMode.HALF_UP);

        PaperTradeEntity e = new PaperTradeEntity();
        e.setTradeId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        e.setEntryDate(event.tradingDate());
        e.setEntryTime(LocalTime.now());
        e.setSymbol(symbol);
        e.setStockName(pick.stockName());
        e.setEntryPrice(entryPrice);
        e.setStopLossPrice(stop);
        e.setTarget1Price(tp1);
        e.setTarget2Price(tp2);
        e.setMaxHoldingDays(DEFAULT_MAX_HOLD_DAYS);
        e.setSource(deriveSource(event.aiStatus()));
        e.setStrategyType(pick.strategyType() != null ? pick.strategyType() : event.strategyType());
        e.setFinalDecisionId(event.finalDecisionId());
        e.setAiTaskId(event.aiTaskId());
        e.setStatus("OPEN");
        e.setPayloadJson(buildEntryPayload(event, pick, entryPrice));
        repository.save(e);
        log.info("[PaperTrade] OPEN id={} symbol={} entry={} stop={} tp1={} tp2={} strategy={}",
                e.getId(), symbol, entryPrice, stop, tp1, tp2, e.getStrategyType());
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
     * @param entryPrice 進場價(必填,>0)
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

        BigDecimal stop = stopLoss != null ? stopLoss
                : entryPrice.multiply(BigDecimal.ONE.subtract(DEFAULT_STOP_LOSS_PCT))
                        .setScale(4, RoundingMode.HALF_UP);
        BigDecimal t1 = tp1 != null ? tp1
                : entryPrice.multiply(BigDecimal.ONE.add(DEFAULT_TARGET1_PCT))
                        .setScale(4, RoundingMode.HALF_UP);
        BigDecimal t2 = tp2 != null ? tp2
                : entryPrice.multiply(BigDecimal.ONE.add(DEFAULT_TARGET2_PCT))
                        .setScale(4, RoundingMode.HALF_UP);

        PaperTradeEntity e = new PaperTradeEntity();
        e.setTradeId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        e.setEntryDate(today);
        e.setEntryTime(LocalTime.now());
        e.setSymbol(symbol);
        e.setEntryPrice(entryPrice.setScale(4, RoundingMode.HALF_UP));
        e.setPositionShares(qty);
        if (qty != null && qty > 0) {
            e.setPositionAmount(entryPrice.multiply(BigDecimal.valueOf(qty))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        e.setStopLossPrice(stop);
        e.setTarget1Price(t1);
        e.setTarget2Price(t2);
        e.setMaxHoldingDays(DEFAULT_MAX_HOLD_DAYS);
        e.setSource("MANUAL");
        e.setStrategyType("SETUP");
        e.setStatus("OPEN");
        e.setPayloadJson(buildManualEntryPayload(symbol, entryPrice, stop, t1, t2, qty, reason));
        repository.save(e);
        log.info("[PaperTrade] recordEntry OPEN id={} symbol={} entry={} stop={} tp1={} tp2={} qty={} reason={}",
                e.getId(), symbol, entryPrice, stop, t1, t2, qty, reason);
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
