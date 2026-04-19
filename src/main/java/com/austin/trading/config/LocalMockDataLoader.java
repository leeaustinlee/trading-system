package com.austin.trading.config;

import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 本地開發資料初始化。
 * 啟動時清除舊有測試資料，重新以最新研究結果填入真實資料。
 * 由 trading.mock-data-loader.enabled=true 控制。
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "trading.mock-data-loader", name = "enabled", havingValue = "true")
public class LocalMockDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalMockDataLoader.class);

    private final MarketSnapshotRepository   marketSnapshotRepository;
    private final TradingStateRepository     tradingStateRepository;
    private final NotificationLogRepository  notificationLogRepository;
    private final CandidateStockRepository   candidateStockRepository;
    private final StockEvaluationRepository  stockEvaluationRepository;
    private final PositionRepository         positionRepository;
    private final DailyPnlRepository         dailyPnlRepository;

    public LocalMockDataLoader(
            MarketSnapshotRepository marketSnapshotRepository,
            TradingStateRepository tradingStateRepository,
            NotificationLogRepository notificationLogRepository,
            CandidateStockRepository candidateStockRepository,
            StockEvaluationRepository stockEvaluationRepository,
            PositionRepository positionRepository,
            DailyPnlRepository dailyPnlRepository
    ) {
        this.marketSnapshotRepository  = marketSnapshotRepository;
        this.tradingStateRepository    = tradingStateRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.candidateStockRepository  = candidateStockRepository;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.positionRepository        = positionRepository;
        this.dailyPnlRepository        = dailyPnlRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // 只重灌「當日研究 seed data」(每次啟動都 refresh)
        // 使用者自建資料 (positions / dailyPnl / notificationLog) 僅在空 table 時 seed，不 deleteAll
        log.info("[DataLoader] 清除當日研究 seed data（candidates/evaluation/market/state）...");
        candidateStockRepository.deleteAll();
        stockEvaluationRepository.deleteAll();
        marketSnapshotRepository.deleteAll();
        tradingStateRepository.deleteAll();

        seedMarketSnapshot();
        seedTradingState();
        seedCandidates();
        seedPositionIfEmpty();
        seedPnlIfEmpty();
        seedNotificationIfEmpty();

        log.info("[DataLoader] 初始化完成。");
    }

    // ── 市場快照（2026-04-17 盤後研究結論）────────────────────────────────────

    private void seedMarketSnapshot() {
        MarketSnapshotEntity e = new MarketSnapshotEntity();
        e.setTradingDate(LocalDate.of(2026, 4, 17));
        e.setMarketGrade("B");
        e.setMarketPhase("偏多待確認");
        e.setDecision("WATCH");
        e.setPayloadJson("""
                {
                  "source": "claude-research-20260417-1750",
                  "txf_close": "N/A（盤後）",
                  "tsm_adr": "375.77 USD (+1.6%)",
                  "tsm_adr_ref": "台積電週一開盤參考 2040–2055 元",
                  "sp500": "+0.26%",
                  "nasdaq": "+0.36%",
                  "filter": "加權需守 36700 + 台積電需守 2000"
                }
                """);
        marketSnapshotRepository.save(e);
        log.info("[DataLoader] market_snapshot 已寫入");
    }

    // ── 交易狀態（盤後 17:50）────────────────────────────────────────────────

    private void seedTradingState() {
        TradingStateEntity e = new TradingStateEntity();
        e.setTradingDate(LocalDate.of(2026, 4, 17));
        e.setMarketGrade("B");
        e.setDecisionLock("無鎖定");
        e.setTimeDecayStage("盤後");
        e.setHourlyGate("收盤");
        e.setMonitorMode("待確認");
        e.setPayloadJson("""
                {"source":"claude-research-20260417-1750","note":"等週一開盤濾網"}
                """);
        tradingStateRepository.save(e);
        log.info("[DataLoader] trading_state 已寫入");
    }

    // ── 候選股（2026-04-17 T86 籌碼確認後排序）──────────────────────────────

    private void seedCandidates() {
        LocalDate d = LocalDate.of(2026, 4, 17);

        // 1. 緯創 3231（信心 7/10，次選但最穩）
        saveCandidateWithEval(d, "3231", "緯創", new BigDecimal("7.0"),
                "大盤殺盤中逆勢抗跌 +1.87%；AI伺服器題材強；T86待確認但推估中性偏多；符合中短線 Level 4 條件",
                "動能追強", "134.0 – 136.5 元",
                new BigDecimal("130.0"), new BigDecimal("145.0"), new BigDecimal("155.0"),
                new BigDecimal("2.4"), true,
                """
                {"rank":1,"confidence":7,"note":"T86待確認，外資預估中性偏多",
                 "risk":"台積電續破2000會拖累AI供應鏈","fund_limit":"3–3.5萬元（約260股）"}
                """);

        // 2. 聯電 2303（信心 6/10，籌碼最強但有法說風險）
        saveCandidateWithEval(d, "2303", "聯電", new BigDecimal("6.0"),
                "外資+5.7萬/投信+1.15萬，年度級別買超；成熟製程漲價題材；⚠️4/22法說會風險，差2個交易日",
                "動能追強", "70.5 – 71.5 元（回測才進，不追）",
                new BigDecimal("68.3"), new BigDecimal("76.0"), new BigDecimal("80.0"),
                new BigDecimal("2.2"), true,
                """
                {"rank":2,"confidence":6,"t86_foreign":57659,"t86_invest_trust":11524,"t86_total":69183,
                 "note":"4/22法說前必須全出或減碼一半","fund_limit":"3萬元（1張）"}
                """);

        // 3. 聯茂 6213（信心 6/10，籌碼凌亂需深回測）
        saveCandidateWithEval(d, "6213", "聯茂", new BigDecimal("6.0"),
                "Q1營收創歷史新高、M9認證落地；收盤+7.34%至270.5元；但外資近5日小賣、籌碼凌亂需深回測",
                "題材成長", "258 – 262 元（深回測才進，不追高）",
                new BigDecimal("248.0"), new BigDecimal("278.0"), new BigDecimal("295.0"),
                new BigDecimal("2.0"), false,
                """
                {"rank":3,"confidence":6,"close":270.5,"change":"+7.34%",
                 "note":"外資近5日小賣、投信承接；直接開盤>276元排除","fund_limit":"3萬元（1張）"}
                """);

        // 4. 台光電 2383（信心 6/10，資金不足，僅族群濾網）
        saveCandidateWithEval(d, "2383", "台光電", new BigDecimal("6.0"),
                "AI PCB基板龍頭；收3,835元+0.66%；⚠️4/29法說；單張380萬超出資金上限，排除實際進場",
                "族群觀察", "3800 – 3835 元（資金不足，觀察用）",
                new BigDecimal("3650.0"), null, null,
                null, false,
                """
                {"rank":4,"confidence":6,"close":3835,"change":"+0.66%",
                 "note":"作CCL族群強弱濾網，不實際進場","reason_exclude":"單張約380萬，超出3–5萬資金上限"}
                """);

        // 5. 奇鋐 3017（信心 5/10，資金不足，排除）
        saveCandidateWithEval(d, "3017", "奇鋐", new BigDecimal("5.0"),
                "AI散熱龍頭；收約2,265元+30；近期法人無積極動作；單張226.5萬超出資金上限，排除",
                "族群觀察", "排除實際進場",
                null, null, null,
                null, false,
                """
                {"rank":5,"confidence":5,"close":2265,"note":"替代觀察：雙鴻3324、建準2421、泰碩3338",
                 "reason_exclude":"單張約226.5萬，超出3–5萬資金上限"}
                """);

        log.info("[DataLoader] 候選股 5 檔已寫入");
    }

    private void saveCandidateWithEval(
            LocalDate date, String symbol, String name,
            BigDecimal score, String reason,
            String valuationMode, String entryZone,
            BigDecimal stopLoss, BigDecimal tp1, BigDecimal tp2,
            BigDecimal rr, boolean includePlan,
            String payloadJson
    ) {
        CandidateStockEntity c = new CandidateStockEntity();
        c.setTradingDate(date);
        c.setSymbol(symbol);
        c.setStockName(name);
        c.setScore(score);
        c.setReason(reason);
        c.setPayloadJson(payloadJson);
        candidateStockRepository.save(c);

        StockEvaluationEntity e = new StockEvaluationEntity();
        e.setTradingDate(date);
        e.setSymbol(symbol);
        e.setValuationMode(valuationMode);
        e.setEntryPriceZone(entryZone);
        e.setStopLossPrice(stopLoss);
        e.setTakeProfit1(tp1);
        e.setTakeProfit2(tp2);
        e.setRiskRewardRatio(rr);
        e.setIncludeInFinalPlan(includePlan);
        stockEvaluationRepository.save(e);
    }

    // ── 現有波段持倉（00631L）────────────────────────────────────────────────

    private void seedPositionIfEmpty() {
        if (positionRepository.count() > 0) {
            log.info("[DataLoader] positions 已有 {} 筆資料，跳過 seed（保留使用者新增持倉）",
                    positionRepository.count());
            return;
        }
        PositionEntity p = new PositionEntity();
        p.setSymbol("00631L");
        p.setSide("做多");
        p.setQty(new BigDecimal("3320"));
        p.setAvgCost(new BigDecimal("22.81"));
        p.setStatus("OPEN");
        p.setOpenedAt(LocalDateTime.of(2026, 4, 8, 9, 30));
        p.setPayloadJson("""
                {"stock_name":"元大台灣50正2","strategy":"波段",
                 "stop_loss":21.21,"target":25.50,
                 "note":"目標價已觸及，移動停利中；4/20需依開盤決定續抱或減碼",
                 "unrealized_pnl_ref":"+9230（估）"}
                """);
        positionRepository.save(p);
        log.info("[DataLoader] 持倉 00631L 已寫入");
    }

    // ── 近日損益（含券商實際損益）────────────────────────────────────────────

    private void seedPnlIfEmpty() {
        if (dailyPnlRepository.count() > 0) {
            log.info("[DataLoader] daily_pnl 已有資料，跳過 seed");
            return;
        }
        // 2026-04-16 波段減碼 + 出清
        DailyPnlEntity p16 = new DailyPnlEntity();
        p16.setTradingDate(LocalDate.of(2026, 4, 16));
        p16.setRealizedPnl(new BigDecimal("19515"));
        p16.setUnrealizedPnl(new BigDecimal("9230"));
        p16.setWinRate(new BigDecimal("100"));
        p16.setPayloadJson("""
                {"gross_pnl":19515,"fee_tax":180,"net_pnl":19335,
                 "trade_count":3,"win_count":3,"loss_count":0,
                 "notes":"00631L波段減碼4張+11110；00675L出清70股+2736；券商確認版"}
                """);
        dailyPnlRepository.save(p16);

        // 2026-04-15 當沖
        DailyPnlEntity p15 = new DailyPnlEntity();
        p15.setTradingDate(LocalDate.of(2026, 4, 15));
        p15.setRealizedPnl(new BigDecimal("-4424"));
        p15.setUnrealizedPnl(BigDecimal.ZERO);
        p15.setWinRate(new BigDecimal("50"));
        p15.setPayloadJson("""
                {"gross_pnl":-4424,"fee_tax":1024,"net_pnl":-5448,
                 "trade_count":2,"win_count":1,"loss_count":1,
                 "notes":"00631L當沖；台玻1802當沖-3900；今日停止追單"}
                """);
        dailyPnlRepository.save(p15);

        log.info("[DataLoader] 損益紀錄 2 筆已寫入");
    }

    // ── 研究完成通知────────────────────────────────────────────────────────────

    private void seedNotificationIfEmpty() {
        if (notificationLogRepository.count() > 0) {
            log.info("[DataLoader] notification_log 已有資料，跳過 seed");
            return;
        }
        NotificationLogEntity n = new NotificationLogEntity();
        n.setEventTime(LocalDateTime.of(2026, 4, 17, 17, 50));
        n.setNotificationType("AI研究");
        n.setSource("Claude");
        n.setTitle("T86籌碼確認 + 明日策略完成 — 2026-04-17");
        n.setContent("""
                研究結論：緯創3231（信心7）＋聯電2303（信心6）為週一首選。
                開盤必要濾網：加權指數守36,700 ＋ 台積電守2,000。
                TSM ADR 4/17 盤中 +1.6%（375.77），台積電週一開盤參考2,040–2,055元。
                ⚠️ 00631L持倉：守25.45續抱，跌破25.00清倉。
                """);
        n.setPayloadJson("""
                {"source":"claude-research-20260417-1750","top1":"3231","top2":"2303",
                 "filter_index":36700,"filter_tsmc":2000,"tsm_adr":375.77}
                """);
        notificationLogRepository.save(n);
        log.info("[DataLoader] 通知紀錄已寫入");
    }
}
