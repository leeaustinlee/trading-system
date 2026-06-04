package com.austin.trading.scheduler;

import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.StopOutcomeLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Daily read-only stop-outcome refresh after paper return backfill. */
@Component
public class StopOutcomeLedgerJob {
    private static final Logger log = LoggerFactory.getLogger(StopOutcomeLedgerJob.class);

    private final StopOutcomeLedgerService stopOutcomeLedgerService;
    private final ObjectProvider<ScoreConfigService> scoreConfigProvider;

    public StopOutcomeLedgerJob(StopOutcomeLedgerService stopOutcomeLedgerService,
                                ObjectProvider<ScoreConfigService> scoreConfigProvider) {
        this.stopOutcomeLedgerService = stopOutcomeLedgerService;
        this.scoreConfigProvider = scoreConfigProvider;
    }

    @Scheduled(cron = "${trading.scheduler.stop-outcome-ledger-cron:0 40 18 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        if (!isEnabled()) {
            log.debug("[StopOutcomeLedger] disabled, skip");
            return;
        }
        StopOutcomeLedgerService.RefreshSummary summary = stopOutcomeLedgerService.refresh(LocalDate.now().minusDays(120), LocalDate.now());
        log.info("[StopOutcomeLedger] refresh done scanned={} eligible={} written={} pendingData={}",
                summary.scanned(), summary.eligible(), summary.written(), summary.pendingData());
    }

    private boolean isEnabled() {
        ScoreConfigService cfg = scoreConfigProvider != null ? scoreConfigProvider.getIfAvailable() : null;
        if (cfg == null) return true;
        return cfg.getBoolean("stop_outcome_ledger.enabled", true);
    }
}
