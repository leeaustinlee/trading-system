package com.austin.trading.workflow;

import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 盤後工作流編排器（15:30 觸發）。
 *
 * <pre>
 * Step 1: 整理今日交易結果（實現損益落表）
 * Step 2: 盤後候選股掃描（30~50 檔強勢股，Phase 2）
 * Step 3: 為明日候選股寫出 Claude 研究請求
 * Step 4: Codex review 請求（Phase 3，若啟用）
 * Step 5: LINE 盤後通知（Phase 3）
 * </pre>
 */
@Service
public class PostmarketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PostmarketWorkflowService.class);

    private final CandidateScanService candidateScanService;
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final ScoreConfigService config;

    public PostmarketWorkflowService(
            CandidateScanService candidateScanService,
            ClaudeCodeRequestWriterService requestWriterService,
            ScoreConfigService config
    ) {
        this.candidateScanService = candidateScanService;
        this.requestWriterService = requestWriterService;
        this.config = config;
    }

    public void execute(LocalDate tradingDate) {
        log.info("[PostmarketWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: TODO Phase 2 - 整理當日倉位損益
        // Step 2: TODO Phase 2 - 全市場盤後強勢股掃描 → ThemeSelectionEngine

        // Step 3: 以今日候選股為基礎，寫出明日 Claude 研究請求
        int researchMax = config.getInt("candidate.research.maxCount", 5);
        var candidates = candidateScanService.getCurrentCandidates(researchMax);
        List<String> symbols = candidates.stream().map(c -> c.symbol()).toList();

        if (!symbols.isEmpty()) {
            boolean written = requestWriterService.writeRequest("POSTMARKET", tradingDate, symbols, null);
            log.info("[PostmarketWorkflow] Claude 研究請求寫出={}, symbols={}", written, symbols);
        }

        // Step 4 + 5: TODO Phase 3
    }
}
