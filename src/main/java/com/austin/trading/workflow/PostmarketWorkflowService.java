package com.austin.trading.workflow;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 盤後工作流編排器（15:30 觸發）。
 *
 * <pre>
 * Step 1: TODO Phase 2 - 整理當日倉位損益
 * Step 2: TODO Phase 2 - 全市場盤後強勢股掃描 → ThemeSelectionEngine
 * Step 3: 以今日候選股為基礎，寫出明日 Claude 研究請求
 * Step 4: LINE 盤後通知（scheduling.line_notify_enabled = true 時）
 * </pre>
 */
@Service
public class PostmarketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PostmarketWorkflowService.class);

    private final CandidateScanService           candidateScanService;
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final LineTemplateService            lineTemplateService;
    private final ScoreConfigService             config;

    public PostmarketWorkflowService(
            CandidateScanService candidateScanService,
            ClaudeCodeRequestWriterService requestWriterService,
            LineTemplateService lineTemplateService,
            ScoreConfigService config
    ) {
        this.candidateScanService = candidateScanService;
        this.requestWriterService = requestWriterService;
        this.lineTemplateService  = lineTemplateService;
        this.config               = config;
    }

    public void execute(LocalDate tradingDate) {
        log.info("[PostmarketWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: TODO Phase 2 - 整理當日倉位損益
        // Step 2: TODO Phase 2 - 全市場盤後強勢股掃描 → ThemeSelectionEngine

        // Step 3: 以今日候選股為基礎，寫出明日 Claude 研究請求
        int researchMax = config.getInt("candidate.research.maxCount", 5);
        List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(researchMax);
        List<String> symbols = candidates.stream().map(CandidateResponse::symbol).toList();

        if (!symbols.isEmpty()) {
            boolean written = requestWriterService.writeRequest("POSTMARKET", tradingDate, symbols, null);
            log.info("[PostmarketWorkflow] Claude 研究請求寫出={}, symbols={}", written, symbols);
        }

        // Step 4: LINE 盤後通知（由 scheduling.line_notify_enabled 控制）
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            int notifyMax = config.getInt("candidate.notify.maxCount", 10);
            List<CandidateResponse> notifyList = candidateScanService.getCurrentCandidates(notifyMax);
            String candidateText = formatCandidates(notifyList);
            lineTemplateService.notifyPostmarket(candidateText, tradingDate);
            log.info("[PostmarketWorkflow] LINE 盤後通知已發送，候選股={} 檔", notifyList.size());
        } else {
            log.info("[PostmarketWorkflow] LINE 通知未啟用（scheduling.line_notify_enabled=false）");
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
