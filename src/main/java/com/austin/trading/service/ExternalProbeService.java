package com.austin.trading.service;

import com.austin.trading.client.AiClaudeClient;
import com.austin.trading.client.AiClaudeClient.AiResponse;
import com.austin.trading.client.TaifexClient;
import com.austin.trading.client.dto.FuturesQuote;
import com.austin.trading.config.AiClaudeConfig;
import com.austin.trading.config.LineNotifyConfig;
import com.austin.trading.dto.response.ExternalProbeHistoryResponse;
import com.austin.trading.dto.response.ExternalProbeItemResponse;
import com.austin.trading.dto.response.ExternalProbeResponse;
import com.austin.trading.entity.ExternalProbeLogEntity;
import com.austin.trading.notify.LineSender;
import com.austin.trading.repository.ExternalProbeLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ExternalProbeService {

    private final TaifexClient taifexClient;
    private final LineSender lineSender;
    private final LineNotifyConfig lineNotifyConfig;
    private final AiClaudeClient aiClaudeClient;
    private final AiClaudeConfig aiClaudeConfig;
    private final ExternalProbeLogRepository externalProbeLogRepository;

    public ExternalProbeService(
            TaifexClient taifexClient,
            LineSender lineSender,
            LineNotifyConfig lineNotifyConfig,
            AiClaudeClient aiClaudeClient,
            AiClaudeConfig aiClaudeConfig,
            ExternalProbeLogRepository externalProbeLogRepository
    ) {
        this.taifexClient = taifexClient;
        this.lineSender = lineSender;
        this.lineNotifyConfig = lineNotifyConfig;
        this.aiClaudeClient = aiClaudeClient;
        this.aiClaudeConfig = aiClaudeConfig;
        this.externalProbeLogRepository = externalProbeLogRepository;
    }

    public ExternalProbeResponse probe(LocalDate taifexDate, boolean liveLine, boolean liveClaude) {
        LocalDate queryDate = taifexDate == null ? LocalDate.now() : taifexDate;
        LocalDateTime checkedAt = LocalDateTime.now();
        ExternalProbeItemResponse taifex = probeTaifex(queryDate);
        ExternalProbeItemResponse line = probeLine(liveLine);
        ExternalProbeItemResponse claude = probeClaude(liveClaude);

        saveLog(checkedAt, queryDate, liveLine, liveClaude, taifex, line, claude);

        return new ExternalProbeResponse(
                checkedAt,
                queryDate,
                liveLine,
                liveClaude,
                taifex,
                line,
                claude
        );
    }

    public List<ExternalProbeHistoryResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return externalProbeLogRepository.findAllByOrderByCheckedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    private ExternalProbeItemResponse probeTaifex(LocalDate date) {
        Optional<FuturesQuote> quote = taifexClient.getTxfQuote(date);
        if (quote.isEmpty()) {
            return new ExternalProbeItemResponse("WARN", false, "TAIFEX 無資料或解析失敗");
        }
        FuturesQuote q = quote.get();
        String detail = String.format("TX close=%s prevClose=%s change=%s volume=%s",
                nullable(q.currentPrice()), nullable(q.prevClose()), nullable(q.change()), nullable(q.volume()));
        return new ExternalProbeItemResponse("OK", true, detail);
    }

    private ExternalProbeItemResponse probeLine(boolean live) {
        if (!lineNotifyConfig.isEnabled()) {
            return new ExternalProbeItemResponse("SKIPPED", false, "LINE 未啟用（trading.line.enabled=false）");
        }
        if (lineNotifyConfig.resolveAccessToken() == null || lineNotifyConfig.resolveAccessToken().isBlank()) {
            return new ExternalProbeItemResponse("WARN", false, "LINE channel access token 未設定");
        }
        if (lineNotifyConfig.getTo() == null || lineNotifyConfig.getTo().isBlank()) {
            return new ExternalProbeItemResponse("WARN", false, "LINE to（群組/使用者 ID）未設定");
        }
        if (!live) {
            return new ExternalProbeItemResponse("OK", true, "LINE 設定已就緒（dry-run）");
        }
        boolean sent = lineSender.send("[Probe] trading-system external probe");
        return sent
                ? new ExternalProbeItemResponse("OK", true, "LINE 實際發送成功")
                : new ExternalProbeItemResponse("ERROR", false, "LINE 實際發送失敗");
    }

    private ExternalProbeItemResponse probeClaude(boolean live) {
        if (!aiClaudeConfig.isEnabled()) {
            return new ExternalProbeItemResponse("SKIPPED", false, "Claude 未啟用（trading.ai.claude.enabled=false）");
        }
        if (aiClaudeConfig.getApiKey() == null || aiClaudeConfig.getApiKey().isBlank()) {
            return new ExternalProbeItemResponse("WARN", false, "Claude API key 未設定");
        }
        if (!live) {
            return new ExternalProbeItemResponse("OK", true, "Claude 設定已就緒（dry-run）");
        }

        AiResponse response = aiClaudeClient.sendMessage(
                "Reply exactly with: OK",
                "You are a health probe. Reply exactly with OK."
        );
        if (response == null) {
            return new ExternalProbeItemResponse("ERROR", false, "Claude 呼叫失敗或無回應");
        }
        String normalized = response.content() == null ? "" : response.content().trim().toUpperCase();
        boolean ok = normalized.contains("OK");
        String detail = String.format("model=%s tokens=%d", response.model(), response.totalTokens());
        return ok
                ? new ExternalProbeItemResponse("OK", true, detail)
                : new ExternalProbeItemResponse("WARN", false, "Claude 有回覆但格式非 OK, " + detail);
    }

    private String nullable(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private void saveLog(
            LocalDateTime checkedAt,
            LocalDate taifexDate,
            boolean liveLine,
            boolean liveClaude,
            ExternalProbeItemResponse taifex,
            ExternalProbeItemResponse line,
            ExternalProbeItemResponse claude
    ) {
        ExternalProbeLogEntity entity = new ExternalProbeLogEntity();
        entity.setCheckedAt(checkedAt);
        entity.setTaifexDate(taifexDate);
        entity.setLiveLine(liveLine);
        entity.setLiveClaude(liveClaude);

        entity.setTaifexStatus(taifex.status());
        entity.setTaifexSuccess(taifex.success());
        entity.setTaifexDetail(truncate(taifex.detail(), 1000));

        entity.setLineStatus(line.status());
        entity.setLineSuccess(line.success());
        entity.setLineDetail(truncate(line.detail(), 1000));

        entity.setClaudeStatus(claude.status());
        entity.setClaudeSuccess(claude.success());
        entity.setClaudeDetail(truncate(claude.detail(), 1000));

        externalProbeLogRepository.save(entity);
    }

    private ExternalProbeHistoryResponse toHistoryResponse(ExternalProbeLogEntity e) {
        return new ExternalProbeHistoryResponse(
                e.getId(),
                e.getCheckedAt(),
                e.getTaifexDate(),
                e.isLiveLine(),
                e.isLiveClaude(),
                new ExternalProbeItemResponse(e.getTaifexStatus(), Boolean.TRUE.equals(e.getTaifexSuccess()), e.getTaifexDetail()),
                new ExternalProbeItemResponse(e.getLineStatus(), Boolean.TRUE.equals(e.getLineSuccess()), e.getLineDetail()),
                new ExternalProbeItemResponse(e.getClaudeStatus(), Boolean.TRUE.equals(e.getClaudeSuccess()), e.getClaudeDetail())
        );
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
