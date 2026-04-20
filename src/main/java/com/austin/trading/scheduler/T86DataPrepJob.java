package com.austin.trading.scheduler;

import com.austin.trading.client.TwseInstitutionalClient;
import com.austin.trading.client.dto.InstitutionalFlow;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 18:10 T86 三大法人確認版排程。
 * <p>
 * 從 TWSE 抓取當日 T86 資料，更新今日 candidate_stock 的 payload_json，
 * 補入外資、投信、自營商淨買超資訊，供 Codex 決策參考。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.t86-data-prep", name = "enabled", havingValue = "true")
public class T86DataPrepJob {

    private static final Logger log = LoggerFactory.getLogger(T86DataPrepJob.class);

    private final TwseInstitutionalClient  institutionalClient;
    private final CandidateStockRepository candidateStockRepository;
    private final SchedulerLogService      schedulerLogService;
    private final DailyOrchestrationService orchestrationService;
    private final AiTaskService            aiTaskService;

    public T86DataPrepJob(
            TwseInstitutionalClient institutionalClient,
            CandidateStockRepository candidateStockRepository,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService
    ) {
        this.institutionalClient      = institutionalClient;
        this.candidateStockRepository = candidateStockRepository;
        this.schedulerLogService      = schedulerLogService;
        this.orchestrationService     = orchestrationService;
        this.aiTaskService            = aiTaskService;
    }

    @Scheduled(cron = "${trading.scheduler.t86-data-prep-cron:0 10 18 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "T86DataPrepJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.T86_DATA_PREP;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            // 1. 抓取今日 T86 全量資料
            List<InstitutionalFlow> flows = institutionalClient.getT86(today);
            if (flows.isEmpty()) {
                log.info("[T86DataPrepJob] No T86 data for {}, skip.", today);
                schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "No T86 data");
                orchestrationService.markDone(today, step, "No T86 data");
                return;
            }

            // 2. 建立 symbol → flow 對應 map
            Map<String, InstitutionalFlow> flowMap = flows.stream()
                    .collect(Collectors.toMap(InstitutionalFlow::symbol, f -> f, (a, b) -> a));

            // 3. 找今日候選股，補入法人資料
            List<CandidateStockEntity> candidates =
                    candidateStockRepository.findByTradingDateOrderByScoreDesc(
                            today, PageRequest.of(0, 20));

            int updated = 0;
            for (CandidateStockEntity entity : candidates) {
                InstitutionalFlow flow = flowMap.get(entity.getSymbol());
                if (flow == null) continue;
                entity.setPayloadJson(mergeInstitutional(entity.getPayloadJson(), flow));
                candidateStockRepository.save(entity);
                updated++;
            }

            // v2.1：建 T86_TOMORROW ai_task（供 Claude 17:50 / Codex 17:58 接手，18:30 TomorrowPlan 讀取）
            try {
                List<AiTaskCandidateRef> refs = candidates.stream()
                        .map(c -> new AiTaskCandidateRef(
                                c.getSymbol(), c.getStockName(), c.getThemeTag(), null))
                        .collect(Collectors.toList());
                aiTaskService.createTask(
                        today, "T86_TOMORROW", null, refs,
                        "18:10 T86 確認後候選（共 " + refs.size() + " 檔），等 Claude 17:50 / Codex 17:58 接手",
                        "D:/ai/stock/claude-research-request.json"
                );
            } catch (Exception e) {
                log.warn("[T86DataPrepJob] createTask 失敗: {}", e.getMessage());
            }

            String msg = String.format("t86_rows=%d candidates=%d updated=%d",
                    flows.size(), candidates.size(), updated);
            log.info("[T86DataPrepJob] {}", msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private String mergeInstitutional(String existingJson, InstitutionalFlow flow) {
        String institutionalData = String.format(
                "\"foreign_net\":%s,\"invest_trust_net\":%s,\"dealer_net\":%s,\"total_institutional_net\":%s" +
                ",\"foreign_and_trust_buy\":%b",
                flow.foreignNet()     != null ? String.valueOf(flow.foreignNet())     : "null",
                flow.investTrustNet() != null ? String.valueOf(flow.investTrustNet()) : "null",
                flow.dealerNet()      != null ? String.valueOf(flow.dealerNet())      : "null",
                flow.totalNet()       != null ? String.valueOf(flow.totalNet())       : "null",
                flow.foreignAndTrustBothBuy()
        );

        if (existingJson == null || existingJson.isBlank() || !existingJson.trim().startsWith("{")) {
            return "{" + institutionalData + "}";
        }
        String trimmed = existingJson.trim();
        if (trimmed.equals("{}")) return "{" + institutionalData + "}";
        return "{" + institutionalData + "," + trimmed.substring(1);
    }
}
