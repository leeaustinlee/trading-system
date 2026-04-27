package com.austin.trading.scheduler;

import com.austin.trading.service.PaperTradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日 13:35 對所有 OPEN paper_trade 進行 mark-to-market:
 * 用 TWSE MIS API 取當日 OHLC,跑 {@link com.austin.trading.engine.exit.FixedRuleExitEvaluator}
 * 判定是否觸發停損/停利/時間出場。
 *
 * <p>cron 預設 {@code 0 35 13 * * MON-FRI}(13:35 收盤後 5 分鐘),可由
 * {@code trading.scheduler.paper-trade-mtm-cron} 覆寫。</p>
 *
 * <p>flag {@code trading.scheduler.paper-trade-mtm.enabled} 預設 true。</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.paper-trade-mtm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaperTradeMtmJob {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeMtmJob.class);

    private final PaperTradeService paperTradeService;

    public PaperTradeMtmJob(PaperTradeService paperTradeService) {
        this.paperTradeService = paperTradeService;
    }

    @Scheduled(cron = "${trading.scheduler.paper-trade-mtm-cron:0 35 13 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDate today = LocalDate.now();
        log.info("[PaperTradeMtmJob] start MTM date={}", today);
        try {
            PaperTradeService.MtmSummary s = paperTradeService.markToMarketAll(today);
            log.info("[PaperTradeMtmJob] done total={} closed={} errors={}", s.total(), s.closed(), s.errors());
        } catch (Exception e) {
            log.error("[PaperTradeMtmJob] MTM failed: {}", e.getMessage(), e);
        }
    }
}
