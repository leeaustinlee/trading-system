package com.austin.trading.service;

import com.austin.trading.domain.enums.AllocationMode;
import com.austin.trading.domain.enums.PositionAction;
import com.austin.trading.domain.enums.PositionSizeLevel;
import com.austin.trading.dto.internal.CapitalAllocationResult;
import com.austin.trading.dto.internal.PositionManagementInput;
import com.austin.trading.dto.internal.PositionManagementInput.SwitchCandidate;
import com.austin.trading.dto.internal.PositionManagementResult;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.request.CodexResultPayloadRequest;
import com.austin.trading.dto.request.CodexReviewedSymbolRequest;
import com.austin.trading.engine.PositionManagementEngine;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.repository.AiTaskRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.service.PositionReviewService.ReviewResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v2.10 Position Management 協調層：把 ReviewResult + live quote + regime +
 * 今日候選池 組成 PositionManagementInput，跑 PositionManagementEngine，
 * 把結果交回呼叫方（FiveMinuteMonitorJob）決定是否發 LINE。
 *
 * <p>本 service 不碰：PositionReviewService、PositionDecisionEngine、
 * StopLossTakeProfitEngine、FinalDecisionService；只做「資料黏合」。</p>
 */
@Service
public class PositionManagementService {

    private static final Logger log = LoggerFactory.getLogger(PositionManagementService.class);

    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");

    private final PositionManagementEngine engine;
    private final MarketRegimeService marketRegimeService;
    private final StockEvaluationRepository stockEvaluationRepository;
    private final StockThemeMappingRepository stockThemeMappingRepository;
    private final CapitalAllocationService capitalAllocationService;
    private final IntradayVwapService intradayVwapService;
    private final VolumeProfileService volumeProfileService;
    private final AiTaskRepository aiTaskRepository;
    private final ObjectMapper objectMapper;

    public PositionManagementService(
            PositionManagementEngine engine,
            MarketRegimeService marketRegimeService,
            StockEvaluationRepository stockEvaluationRepository,
            StockThemeMappingRepository stockThemeMappingRepository,
            CapitalAllocationService capitalAllocationService,
            IntradayVwapService intradayVwapService,
            VolumeProfileService volumeProfileService,
            AiTaskRepository aiTaskRepository,
            ObjectMapper objectMapper
    ) {
        this.engine = engine;
        this.marketRegimeService = marketRegimeService;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.stockThemeMappingRepository = stockThemeMappingRepository;
        this.capitalAllocationService = capitalAllocationService;
        this.intradayVwapService = intradayVwapService;
        this.volumeProfileService = volumeProfileService;
        this.aiTaskRepository = aiTaskRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 對一批 {@link ReviewResult} 逐筆跑 PositionManagementEngine。
     *
     * @param reviews       FiveMinuteMonitorJob 從 PositionReviewService 拿到的結果
     * @param tradingDate   今日
     * @return 每檔持倉對應一個 PositionManagementResult
     */
    public List<PositionManagementResult> evaluateAll(List<ReviewResult> reviews, LocalDate tradingDate) {
        if (reviews == null || reviews.isEmpty()) return List.of();

        // 共用上下文：regime + 今日候選池（for SWITCH_HINT）
        String regimeType = marketRegimeService.getLatestForToday()
                .map(MarketRegimeDecision::regimeType)
                .orElse(null);
        List<SwitchCandidateSnapshot> todayCandidates = loadTodayCandidatePool(tradingDate);

        List<PositionManagementResult> results = new ArrayList<>();
        for (ReviewResult r : reviews) {
            try {
                results.add(evaluateOne(r, regimeType, todayCandidates, tradingDate));
            } catch (Exception e) {
                log.warn("[PositionManagement] evaluate failed symbol={}: {}",
                        r.position() == null ? "?" : r.position().getSymbol(), e.getMessage());
            }
        }
        return results;
    }

    public PositionManagementResult evaluateOne(
            ReviewResult review,
            String regimeType,
            List<SwitchCandidateSnapshot> todayCandidates,
            LocalDate tradingDate
    ) {
        PositionEntity pos = review.position();
        PositionStatus baseline = review.decision() == null ? null : review.decision().status();
        BigDecimal currentScore = fetchCurrentPositionScore(tradingDate, pos.getSymbol()).orElse(null);
        String positionTheme = resolveTheme(pos).orElse(null);
        // positionSizeLevel 存 payloadJson key "positionSizeLevel"（PositionEntity 無專屬欄位，MVP）
        PositionSizeLevel sizeLevel = PositionSizeLevel.parseOrDefault(
                parseStringFromPayload(pos.getPayloadJson(), "positionSizeLevel"),
                PositionSizeLevel.NORMAL);

        // MVP：peakUnrealizedPct / todayAddCount / lifetimeAddCount 先讀 payloadJson 的 placeholder；
        // 若無 → null / 0（保守）。未來可接 position_review_log 聚合或新欄位。
        BigDecimal peakPct = parseBigDecimalFromPayload(pos.getPayloadJson(), "peakUnrealizedPct");
        int todayAdds    = parseIntFromPayload(pos.getPayloadJson(), "todayAddCount", 0);
        int lifetimeAdds = parseIntFromPayload(pos.getPayloadJson(), "lifetimeAddCount", 0);

        List<SwitchCandidate> switchPool = buildSwitchPool(
                todayCandidates, pos.getSymbol(), positionTheme);

        // v2.12 Fix1：Position ADD 資料鏈。從今日最新 Codex payload 抓 volume/turnover/avgDailyVolume，
        // 算 VWAP + volumeRatio 餵進 engine；若資料缺失仍傳 null（engine 保守 fallback），
        // 但 service 層補 trace flag（vwapAvailable / volumeRatioAvailable）供事後 debug。
        VolumeDataProbe probe = buildVolumeDataProbe(pos.getSymbol(), tradingDate);

        PositionManagementInput in = new PositionManagementInput(
                pos.getSymbol(),
                baseline,
                regimeType,
                review.currentPrice(),
                pos.getAvgCost(),
                pos.getStopLossPrice(),
                pos.getTrailingStopPrice(),
                null,         // sessionHigh MVP：live quote 未傳；engine 會在 ADD 路徑判 null 視為 fail
                peakPct,
                probe.vwapPrice(),
                probe.volumeRatio(),
                sizeLevel,
                todayAdds,
                lifetimeAdds,
                currentScore,
                positionTheme,
                switchPool
        );

        PositionManagementResult raw = engine.evaluate(in);
        PositionManagementResult result = decorateWithVolumeTrace(raw, probe);
        log.info("[PositionManagement] {} action={} reason={} baseline={} regime={} vwapAvailable={} volumeRatioAvailable={}",
                pos.getSymbol(), result.action(), result.reason(), baseline, regimeType,
                probe.vwapAvailable(), probe.volumeRatioAvailable());
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Volume data probe：v2.12 Fix1
    // ══════════════════════════════════════════════════════════════════════

    /** 為單一 symbol 計算 VWAP / volumeRatio，並記錄 available flag + reason。 */
    VolumeDataProbe buildVolumeDataProbe(String symbol, LocalDate tradingDate) {
        Optional<CodexReviewedSymbolRequest> codex = fetchCodexReviewedForSymbol(symbol, tradingDate);
        if (codex.isEmpty()) {
            return VolumeDataProbe.unavailable("NO_CODEX_REVIEW_FOR_SYMBOL", "NO_CODEX_REVIEW_FOR_SYMBOL");
        }
        CodexReviewedSymbolRequest item = codex.get();
        IntradayVwapService.VwapResult vwap =
                intradayVwapService.computeFromCumulative(item.volume(), item.turnover());
        LocalTime now = LocalTime.now(TPE);
        VolumeProfileService.VolumeRatioResult vr =
                volumeProfileService.compute(item.volume(), item.averageDailyVolume(), now);
        return new VolumeDataProbe(
                vwap.available() ? vwap.price() : null,
                vwap.available(),
                vwap.available() ? null : vwap.reason(),
                vr.available() ? vr.ratio() : null,
                vr.available(),
                vr.available() ? null : vr.reason()
        );
    }

    /**
     * 從今日的 AiTask（偏好 OPENING → MIDDAY → 其他）裡讀 Codex result payload，抓出 symbol 的
     * reviewed entry（含 volume / turnover / averageDailyVolume）。找不到回 Optional.empty。
     */
    private Optional<CodexReviewedSymbolRequest> fetchCodexReviewedForSymbol(String symbol, LocalDate tradingDate) {
        if (symbol == null || tradingDate == null) return Optional.empty();
        List<AiTaskEntity> tasks = aiTaskRepository.findByTradingDateOrderByCreatedAtDesc(tradingDate);
        if (tasks == null || tasks.isEmpty()) return Optional.empty();
        // 偏好順序：OPENING 最新、再退 MIDDAY、再退 POSTMARKET（盤後）/ PREMARKET（量能資料可能不足）
        String[] typePreference = {"OPENING", "MIDDAY", "POSTMARKET", "PREMARKET"};
        for (String type : typePreference) {
            for (AiTaskEntity task : tasks) {
                if (!type.equalsIgnoreCase(task.getTaskType())) continue;
                Optional<CodexReviewedSymbolRequest> hit = parseCodexForSymbol(task, symbol);
                if (hit.isPresent()) return hit;
            }
        }
        // fallback：任何 task type
        for (AiTaskEntity task : tasks) {
            Optional<CodexReviewedSymbolRequest> hit = parseCodexForSymbol(task, symbol);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    private Optional<CodexReviewedSymbolRequest> parseCodexForSymbol(AiTaskEntity task, String symbol) {
        String json = task.getCodexPayloadJson();
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            CodexResultPayloadRequest payload = objectMapper.readValue(json, CodexResultPayloadRequest.class);
            Optional<CodexReviewedSymbolRequest> hit = findSymbolIn(payload.selected(), symbol);
            if (hit.isPresent()) return hit;
            hit = findSymbolIn(payload.watchlist(), symbol);
            if (hit.isPresent()) return hit;
            return findSymbolIn(payload.rejected(), symbol);
        } catch (Exception e) {
            log.debug("[PositionManagement] parse codex payload failed taskId={}: {}",
                    task.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<CodexReviewedSymbolRequest> findSymbolIn(
            List<CodexReviewedSymbolRequest> list, String symbol) {
        if (list == null) return Optional.empty();
        for (CodexReviewedSymbolRequest item : list) {
            if (item != null && symbol.equals(item.symbol())) return Optional.of(item);
        }
        return Optional.empty();
    }

    /** 把 VWAP / volumeRatio 的 available flag + reason 塞進 result.trace，讓事後能 debug。 */
    private static PositionManagementResult decorateWithVolumeTrace(
            PositionManagementResult raw, VolumeDataProbe probe) {
        if (raw == null) return null;
        Map<String, Object> trace = new LinkedHashMap<>();
        if (raw.trace() != null) trace.putAll(raw.trace());
        trace.put("vwapAvailable", probe.vwapAvailable());
        if (!probe.vwapAvailable() && probe.vwapUnavailableReason() != null) {
            trace.put("vwapUnavailableReason", probe.vwapUnavailableReason());
        }
        trace.put("volumeRatioAvailable", probe.volumeRatioAvailable());
        if (!probe.volumeRatioAvailable() && probe.volumeRatioUnavailableReason() != null) {
            trace.put("volumeRatioUnavailableReason", probe.volumeRatioUnavailableReason());
        }
        return new PositionManagementResult(
                raw.symbol(), raw.action(), raw.reason(),
                raw.currentPrice(), raw.entryPrice(), raw.unrealizedPct(),
                raw.vwapPrice(), raw.volumeRatio(),
                raw.stopLoss(), raw.trailingStop(),
                raw.score(), raw.positionSizeLevel(),
                raw.signals(), raw.warnings(),
                java.util.Collections.unmodifiableMap(trace));
    }

    /** VWAP + volumeRatio 探測結果 + trace flag。 */
    public record VolumeDataProbe(
            BigDecimal vwapPrice,
            boolean vwapAvailable,
            String vwapUnavailableReason,
            BigDecimal volumeRatio,
            boolean volumeRatioAvailable,
            String volumeRatioUnavailableReason
    ) {
        public static VolumeDataProbe unavailable(String vwapReason, String volRatioReason) {
            return new VolumeDataProbe(null, false, vwapReason, null, false, volRatioReason);
        }
    }

    /**
     * v2.11：對非 HOLD 的 PositionManagement 結果補上資金配置建議。
     * HOLD / null action / currentPrice 缺失 → 直接回 null（caller 不用 LINE）。
     */
    public CapitalAllocationResult resolveAllocation(PositionManagementResult mgmt,
                                                      com.austin.trading.entity.PositionEntity pos,
                                                      String regimeType) {
        if (mgmt == null || mgmt.action() == null) return null;
        if (mgmt.action() == PositionAction.HOLD) return null;
        if (pos == null) return null;
        AllocationMode mode = AllocationMode.parseOrDefault(
                mgmt.positionSizeLevel() == null ? null : mgmt.positionSizeLevel().name(),
                AllocationMode.NORMAL);
        java.math.BigDecimal entry = pos.getAvgCost();
        java.math.BigDecimal current = mgmt.currentPrice();
        java.math.BigDecimal stop = pos.getStopLossPrice();
        java.math.BigDecimal target = pos.getTakeProfit1();
        String theme = mgmt.trace() == null ? null : (String) mgmt.trace().get("theme");
        // Fallback: re-resolve theme if not in trace（保守）
        if (theme == null) theme = resolveThemeTag(pos.getSymbol());

        return switch (mgmt.action()) {
            case ADD -> capitalAllocationService.allocateForAdd(
                    pos.getSymbol(), theme, mgmt.score(), entry, current, stop, target, regimeType, mode);
            case REDUCE -> capitalAllocationService.allocateForReduce(
                    pos.getSymbol(), theme, mgmt.score(), entry, current, stop, target, regimeType, mode);
            case SWITCH_HINT -> capitalAllocationService.allocateForSwitchOldLeg(
                    pos.getSymbol(), theme, mgmt.score(), entry, current, stop, target, regimeType, mode);
            default -> null;   // EXIT：既有 EXIT 警報不需要 allocation，由 LINE 出場訊息直接處理
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // 輔助：candidate pool / theme
    // ══════════════════════════════════════════════════════════════════════

    private List<SwitchCandidateSnapshot> loadTodayCandidatePool(LocalDate tradingDate) {
        List<StockEvaluationEntity> evaluations = stockEvaluationRepository.findByTradingDate(tradingDate);
        if (evaluations == null || evaluations.isEmpty()) return List.of();
        List<SwitchCandidateSnapshot> list = new ArrayList<>();
        for (StockEvaluationEntity e : evaluations) {
            if (e.getFinalRankScore() == null) continue;
            // MVP：bucket / mainStream 未存在 evaluation；先用 finalRankScore 強度代替
            //       實戰上 FiveMinuteMonitorJob 之後可覆寫為 Codex bucket（若可取）。
            String bucket = "SELECT_BUY_NOW"; // 保守假設：有 eval 分數的都是候選池
            list.add(new SwitchCandidateSnapshot(
                    e.getSymbol(), e.getFinalRankScore(), bucket, null, false));
        }
        return list;
    }

    private List<SwitchCandidate> buildSwitchPool(
            List<SwitchCandidateSnapshot> todayCandidates,
            String positionSymbol,
            String positionTheme
    ) {
        if (todayCandidates == null || todayCandidates.isEmpty()) return List.of();
        List<SwitchCandidate> out = new ArrayList<>();
        for (SwitchCandidateSnapshot s : todayCandidates) {
            if (s.symbol().equals(positionSymbol)) continue;
            String candidateTheme = s.themeTag();
            if (candidateTheme == null && positionTheme != null) {
                // 查該 symbol 是否與持倉同主題（MVP：取第一個 active theme）
                candidateTheme = resolveThemeTag(s.symbol());
            }
            boolean themeMatch = positionTheme == null
                    || (candidateTheme != null && candidateTheme.equalsIgnoreCase(positionTheme));
            if (!themeMatch) continue;
            out.add(new SwitchCandidate(
                    s.symbol(), s.finalRankScore(), s.bucket(), candidateTheme, s.mainStream()));
        }
        return out;
    }

    private Optional<BigDecimal> fetchCurrentPositionScore(LocalDate tradingDate, String symbol) {
        return stockEvaluationRepository.findByTradingDateAndSymbol(tradingDate, symbol)
                .map(StockEvaluationEntity::getFinalRankScore);
    }

    private Optional<String> resolveTheme(PositionEntity pos) {
        // 1. payloadJson 裡若有 themeTag 取之
        String fromPayload = parseStringFromPayload(pos.getPayloadJson(), "themeTag");
        if (fromPayload != null && !fromPayload.isBlank()) return Optional.of(fromPayload);
        // 2. stock_theme_mapping 找第一個 active
        String tag = resolveThemeTag(pos.getSymbol());
        return Optional.ofNullable(tag);
    }

    private String resolveThemeTag(String symbol) {
        List<StockThemeMappingEntity> mappings =
                stockThemeMappingRepository.findBySymbolAndIsActiveTrue(symbol);
        if (mappings == null || mappings.isEmpty()) return null;
        return mappings.get(0).getThemeTag();
    }

    // ── payload_json 極簡解析（非正規，只為 MVP；未來應換 Jackson）──
    private BigDecimal parseBigDecimalFromPayload(String payload, String key) {
        String raw = parseStringFromPayload(payload, key);
        if (raw == null) return null;
        try { return new BigDecimal(raw.trim()); } catch (NumberFormatException e) { return null; }
    }

    private int parseIntFromPayload(String payload, String key, int fallback) {
        String raw = parseStringFromPayload(payload, key);
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private String parseStringFromPayload(String payload, String key) {
        if (payload == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int idx = payload.indexOf(needle);
        if (idx < 0) return null;
        int colon = payload.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < payload.length() && (payload.charAt(start) == ' ' || payload.charAt(start) == '"')) start++;
        int end = start;
        while (end < payload.length() && "\",}]\n".indexOf(payload.charAt(end)) < 0) end++;
        String raw = payload.substring(start, end).trim();
        return raw.isEmpty() ? null : raw;
    }

    /** 今日候選池精簡 snapshot，避免直接洩漏 entity。 */
    public record SwitchCandidateSnapshot(
            String symbol,
            BigDecimal finalRankScore,
            String bucket,
            String themeTag,
            boolean mainStream
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", symbol);
            m.put("finalRankScore", finalRankScore);
            m.put("bucket", bucket);
            m.put("themeTag", themeTag);
            m.put("mainStream", mainStream);
            return m;
        }
    }
}
