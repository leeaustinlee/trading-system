package com.austin.trading.scheduler;

import com.austin.trading.service.PaperTradeService;
import com.austin.trading.service.PaperTradeService.AutoExitCycleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Auto-exit cron for paper trades.
 *
 * <p>Fires every 5 minutes during the regular Taiwan trading session
 * ({@code 0 */5 9-13 * * MON-FRI}, {@code Asia/Taipei}). For each OPEN paper_trade row
 * it evaluates the 7 priority-ordered triggers in
 * {@link PaperTradeService#attemptExit} and writes an audit row to
 * {@code paper_trade_exit_log} either way (FIRED / SKIPPED_NOT_TRIGGERED).</p>
 *
 * <p>Gated by both:</p>
 * <ul>
 *   <li>{@code trading.paper_mode.enabled} (DB flag, default TRUE) — kills the entire paper-trade pipeline</li>
 *   <li>{@code paper.auto_exit.enabled} (DB flag, default TRUE) — only kills auto-exit, manual recordEntry still works</li>
 * </ul>
 *
 * <p>The {@code @ConditionalOnProperty} on {@code trading.scheduler.paper-trade-exit.enabled}
 * is the static (application.yml) on/off; it defaults to TRUE so the bean registers in prod.</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.paper-trade-exit",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaperTradeExitJob {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeExitJob.class);

    private final PaperTradeService paperTradeService;

    public PaperTradeExitJob(PaperTradeService paperTradeService) {
        this.paperTradeService = paperTradeService;
    }

    @Scheduled(cron = "${trading.scheduler.paper-trade-exit-cron:0 */5 9-13 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public AutoExitCycleResult run() {
        try {
            AutoExitCycleResult r = paperTradeService.runAutoExitCycle();
            log.info("[PaperTradeExitJob] cycle complete checked={} exited={} errors={}",
                    r.checked(), r.exited(), r.errors());
            return r;
        } catch (Exception e) {
            log.error("[PaperTradeExitJob] cycle failed: {}", e.getMessage(), e);
            return new AutoExitCycleResult(0, 0, 1);
        }
    }
}
