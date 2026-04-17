package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.SchedulerLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 15:30 盤後分析排程。
 * 讀取今日候選名單，發送 LINE 盤後通知。
 * 注意：候選名單由 Codex 或外部掃描工具寫入 candidate_stock 表，本 Job 只讀取並通知。
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.postmarket-analysis", name = "enabled", havingValue = "true")
public class PostmarketAnalysis1530Job {

    private static final Logger log = LoggerFactory.getLogger(PostmarketAnalysis1530Job.class);

    private final CandidateScanService candidateScanService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;

    public PostmarketAnalysis1530Job(
            CandidateScanService candidateScanService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService
    ) {
        this.candidateScanService = candidateScanService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.postmarket-analysis-cron:0 30 15 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PostmarketAnalysis1530Job";
        try {
            LocalDate today = LocalDate.now();

            // 讀取今日候選（最多 10 檔：超強勢 5 + 中短線 5）
            List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(10);
            String candidateText = formatCandidates(candidates);

            lineTemplateService.notifyPostmarket(candidateText, today);

            log.info("[PostmarketAnalysis1530Job] done, candidates={}", candidates.size());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(),
                    "candidates=" + candidates.size());
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private String formatCandidates(List<CandidateResponse> list) {
        if (list.isEmpty()) return "（今日無候選資料，請確認掃描流程）";
        return list.stream()
                .map(c -> {
                    String name = c.stockName() == null ? "" : " " + c.stockName();
                    String zone = c.entryPriceZone() == null ? "" : "  區間：" + c.entryPriceZone();
                    String rr   = c.riskRewardRatio() == null ? "" :
                            String.format("  RR：%.2f", c.riskRewardRatio().doubleValue());
                    return "  ▶ " + c.symbol() + name + zone + rr;
                })
                .collect(Collectors.joining("\n"));
    }
}
