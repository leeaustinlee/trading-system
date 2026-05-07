package com.austin.trading.scheduler;

import com.austin.trading.service.TuningAfterTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class TuningAfterTrackingJob {
    private static final Logger log = LoggerFactory.getLogger(TuningAfterTrackingJob.class);
    private final TuningAfterTrackingService service;

    public TuningAfterTrackingJob(TuningAfterTrackingService service) {
        this.service = service;
    }

    @Scheduled(cron = "${trading.scheduler.tuning-after-tracking-cron:0 30 19 * * MON-FRI}",
            zone = "${trading.timezone:Asia/Taipei}")
    public void runDaily() {
        var written = service.run(LocalDate.now());
        log.info("[TuningAfterTrackingJob] wrote {} after metrics rows", written.size());
    }
}
