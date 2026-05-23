package com.austin.trading.scheduler;

import com.austin.trading.client.TwseInstitutionalClient;
import com.austin.trading.client.dto.InstitutionalFlow;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ThemeLeaderRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final ThemeLeaderRetentionService themeLeaderRetentionService;

    @Autowired
    public T86DataPrepJob(
            TwseInstitutionalClient institutionalClient,
            CandidateStockRepository candidateStockRepository,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            ClaudeCodeRequestWriterService requestWriterService,
            ThemeLeaderRetentionService themeLeaderRetentionService
    ) {
        this.institutionalClient      = institutionalClient;
        this.candidateStockRepository = candidateStockRepository;
        this.schedulerLogService      = schedulerLogService;
        this.orchestrationService     = orchestrationService;
        this.aiTaskService            = aiTaskService;
        this.requestWriterService     = requestWriterService;
        this.themeLeaderRetentionService = themeLeaderRetentionService;
    }

    /** Backward-compatible constructor for legacy tests. */
    public T86DataPrepJob(
            TwseInstitutionalClient institutionalClient,
            CandidateStockRepository candidateStockRepository,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            ClaudeCodeRequestWriterService requestWriterService
    ) {
        this(institutionalClient, candidateStockRepository, schedulerLogService,
                orchestrationService, aiTaskService, requestWriterService, null);
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
                schedulerLogService.emptyData(jobName, triggerTime, LocalDateTime.now(), "No T86 data");
                orchestrationService.markDone(today, step, "No T86 data");
                return;
            }

            // 2. 建立 symbol → flow 對應 map
            Map<String, InstitutionalFlow> flowMap = flows.stream()
                    .collect(Collectors.toMap(InstitutionalFlow::symbol, f -> f, (a, b) -> a));

            // 3. 找隔日計畫候選股。T86_TOMORROW 目的是為「明日」決策補上 T86 籌碼，因此
            //    優先使用 DB 最新一筆 (next-day universe)；若 DB 沒有任何候選，才退回 today。
            LocalDate candidateTradingDate = resolveCandidateTradingDate(today);
            List<CandidateStockEntity> candidates =
                    candidateStockRepository.findByTradingDateOrderByScoreDesc(
                            candidateTradingDate, PageRequest.of(0, 20));

            int updated = 0;
            for (CandidateStockEntity entity : candidates) {
                InstitutionalFlow flow = flowMap.get(entity.getSymbol());
                if (flow == null) continue;
                entity.setPayloadJson(mergeInstitutional(entity.getPayloadJson(), flow));
                candidateStockRepository.save(entity);
                updated++;
            }

            // v2.6：建 T86_TOMORROW ai_task（供 Claude / Codex / TomorrowPlan 接手）。
            // 若 task 建立失敗，直接 fail-fast，避免寫出沒有 taskId 的 request。
            List<String> symbols = candidates.stream()
                    .map(CandidateStockEntity::getSymbol)
                    .collect(Collectors.toList());
            Long t86TaskId = null;
            try {
                List<AiTaskCandidateRef> refs = candidates.stream()
                        .map(c -> new AiTaskCandidateRef(
                                c.getSymbol(), c.getStockName(), c.getThemeTag(), null))
                        .collect(Collectors.toList());
                var task = aiTaskService.createTask(
                        today, "T86_TOMORROW", null, refs,
                        "18:10 T86 確認後候選（共 " + refs.size() + " 檔），等 Claude/Codex/TomorrowPlan 接手",
                        "D:/ai/stock/claude-research-request.json"
                );
                t86TaskId = task.getId();
            } catch (Exception e) {
                log.warn("[T86DataPrepJob] createTask 失敗: {}", e.getMessage());
            }

            if (t86TaskId == null) {
                throw new IllegalStateException("T86_TOMORROW task 建立失敗，拒絕寫出無 taskId request");
            }

            // v2.5：寫 Claude 研究請求檔帶 taskId + allowed_symbols
            try {
                String context = String.format("{\"t86_rows\":%d,\"candidates_with_flow\":%d}",
                        flows.size(), updated);
                List<ClaudeCodeRequestWriterService.LeaderContext> leaders = themeLeaderRetentionService == null
                        ? List.of()
                        : themeLeaderRetentionService.loadLeaderContexts(today, "T86_TOMORROW");
                boolean requestWritten = requestWriterService.writeRequest(t86TaskId, "T86_TOMORROW", today, symbols, leaders, context);
                if (!requestWritten) {
                    throw new IllegalStateException("T86_TOMORROW request 寫出失敗");
                }
            } catch (Exception e) {
                log.warn("[T86DataPrepJob] writeRequest 失敗: {}", e.getMessage());
                throw new IllegalStateException("T86_TOMORROW request 寫出失敗，拒絕留下只有 task、沒有 file bridge request 的狀態", e);
            }

            String msg = String.format("t86_rows=%d candidateSourceDate=%s candidates=%d updated=%d",
                    flows.size(), candidateTradingDate, candidates.size(), updated);
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

    /**
     * 2026-05-13 fix：T86_TOMORROW 是「明日候選 + T86 籌碼補強」流程，因此候選來源優先取
     * DB 最新一筆 tradingDate（通常已經是 next-day universe），而非 today。
     * 順序：LATEST → TODAY。Package-private 供測試使用。
     */
    LocalDate resolveCandidateTradingDate(LocalDate today) {
        Optional<LocalDate> latest = candidateStockRepository
                .findTopByOrderByTradingDateDesc()
                .map(CandidateStockEntity::getTradingDate);
        if (latest.isPresent()) {
            if (!latest.get().equals(today)) {
                log.info("[T86DataPrepJob] T86_TOMORROW 採用最新候選交易日 {}（非 today={}）",
                        latest.get(), today);
            }
            return latest.get();
        }
        boolean hasTodayCandidates = !candidateStockRepository
                .findByTradingDateOrderByScoreDesc(today, PageRequest.of(0, 1))
                .isEmpty();
        if (hasTodayCandidates) return today;
        return today;
    }

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
