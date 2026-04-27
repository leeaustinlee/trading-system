package com.austin.trading.controller;

import com.austin.trading.scheduler.PaperTradeExitJob;
import com.austin.trading.service.PaperTradeService.AutoExitCycleResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual hook to kick the auto-exit cron out-of-band (e.g. from ops scripts).
 *
 * <p>{@code POST /api/paper/exit-check} → returns {@link AutoExitCycleResult}
 * (checked / exited / errors).</p>
 *
 * <p>The {@code PaperTradeExitJob} bean is wrapped in {@code ObjectProvider} so the
 * controller still loads when the job is disabled via property — the endpoint then
 * returns a 503-ish empty cycle.</p>
 */
@RestController
@RequestMapping("/api/paper")
public class PaperExitController {

    private final ObjectProvider<PaperTradeExitJob> jobProvider;

    @Autowired
    public PaperExitController(ObjectProvider<PaperTradeExitJob> jobProvider) {
        this.jobProvider = jobProvider;
    }

    @PostMapping("/exit-check")
    public AutoExitCycleResult exitCheck() {
        PaperTradeExitJob job = jobProvider.getIfAvailable();
        if (job == null) {
            return new AutoExitCycleResult(0, 0, 0);
        }
        return job.run();
    }
}
