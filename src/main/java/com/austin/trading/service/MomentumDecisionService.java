package com.austin.trading.service;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.engine.MomentumCandidateEngine;
import com.austin.trading.engine.MomentumScoringEngine;
import com.austin.trading.engine.VetoEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * v2.3 Momentum Chase 決策服務。
 * <p>
 * 輸入：Setup pipeline 已 enrich 過的 {@link FinalDecisionCandidateRequest} 清單（含 themeRank、
 * finalThemeScore、claudeScore、codexScore、entryTooExtended、volumeSpike 等）+ 市場狀態；
 * 額外從 {@link CandidateStockRepository} / {@link StockEvaluationRepository} 補 today change pct
 * 與 claude risk flags。
 * </p>
 * <p>
 * 流程：對每檔跑 {@link MomentumCandidateEngine} 基本篩選、{@link VetoEngine} MOMENTUM 分支
 * （拿 scoringPenalty）、{@link MomentumScoringEngine} 算總分；達 {@code momentum.entry_score_min}
 * 門檻、無 hard veto、且為 momentum candidate 者入選。
 * </p>
 * <p>
 * 熱開關：{@code momentum.enabled=false} 時直接回空。
 * 觀察模式：{@code momentum.observation_mode=true} 時不輸出進場標的，只 log / 供前端顯示 WATCH。
 * </p>
 */
@Service
public class MomentumDecisionService {

    private static final Logger log = LoggerFactory.getLogger(MomentumDecisionService.class);

    private final ScoreConfigService         config;
    private final MomentumCandidateEngine    candidateEngine;
    private final MomentumScoringEngine      scoringEngine;
    private final VetoEngine                 vetoEngine;
    private final CandidateStockRepository   candidateStockRepository;
    private final StockEvaluationRepository  stockEvaluationRepository;
    private final ObjectMapper               objectMapper;

    public MomentumDecisionService(
            ScoreConfigService config,
            MomentumCandidateEngine candidateEngine,
            MomentumScoringEngine scoringEngine,
            VetoEngine vetoEngine,
            CandidateStockRepository candidateStockRepository,
            StockEvaluationRepository stockEvaluationRepository,
            ObjectMapper objectMapper
    ) {
        this.config = config;
        this.candidateEngine = candidateEngine;
        this.scoringEngine = scoringEngine;
        this.vetoEngine = vetoEngine;
        this.candidateStockRepository = candidateStockRepository;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.objectMapper = objectMapper;
    }

    public record MomentumPick(
            FinalDecisionSelectedStockResponse selected,
            BigDecimal momentumScore,
            MomentumScoringEngine.MomentumTier tier,
            Map<String, Object> details
    ) {}

    public record MomentumResultBundle(
            List<MomentumPick> picks,
            boolean observationMode,
            int basicCandidatesCount,   // 有幾檔通過 candidate 篩選
            int enterThresholdCount     // 有幾檔分數 >= entry_score_min
    ) {}

    public MomentumResultBundle evaluate(
            LocalDate tradingDate,
            List<FinalDecisionCandidateRequest> scoredCandidates,
            String marketGrade,
            String decisionLock,
            String timeDecay
    ) {
        if (!config.getBoolean("momentum.enabled", true)) {
            log.debug("[Momentum] disabled by config");
            return new MomentumResultBundle(List.of(), false, 0, 0);
        }
        if (scoredCandidates == null || scoredCandidates.isEmpty()) {
            return new MomentumResultBundle(List.of(), false, 0, 0);
        }
        // 市場 C 級整體禁止，Momentum 也不做（避免踩地雷）
        if ("C".equalsIgnoreCase(marketGrade)) {
            log.info("[Momentum] market grade C → skip");
            return new MomentumResultBundle(List.of(), false, 0, 0);
        }

        boolean observationMode = config.getBoolean("momentum.observation_mode", true);
        BigDecimal entryMin = config.getDecimal("momentum.entry_score_min", new BigDecimal("7.5"));
        int maxPicks = config.getInt("momentum.max_picks_per_day", 1);

        List<MomentumPick> qualifiedAll = new ArrayList<>();
        int basicQualified = 0;
        int overEntryThreshold = 0;

        for (FinalDecisionCandidateRequest c : scoredCandidates) {
            MomentumAuxData aux = loadAuxData(tradingDate, c.stockCode());

            MomentumCandidateEngine.CandidateDecision cd = candidateEngine.evaluate(
                    buildCandidateInput(c, aux));
            if (!cd.isMomentumCandidate()) continue;
            basicQualified++;

            VetoEngine.VetoInput vin = buildVetoInput(c, aux);
            VetoEngine.VetoResult veto = vetoEngine.evaluate(vin, StrategyType.MOMENTUM_CHASE);
            if (veto.vetoed()) {
                log.debug("[Momentum] {} hard vetoed: {}", c.stockCode(), veto.reasons());
                continue;
            }

            MomentumScoringEngine.MomentumResult score = scoringEngine.compute(
                    buildScoringInput(c, aux), veto.scoringPenalty());
            MomentumScoringEngine.MomentumTier tier = scoringEngine.classify(score.momentumScore());
            if (tier == MomentumScoringEngine.MomentumTier.BELOW_WATCH) continue;

            if (score.momentumScore().compareTo(entryMin) >= 0) {
                overEntryThreshold++;
                if (observationMode) {
                    log.info("[Momentum] observation-mode: {} score={} would ENTER — held as WATCH only",
                            c.stockCode(), score.momentumScore());
                    continue; // 觀察模式不產 pick
                }
                qualifiedAll.add(buildPick(c, aux, score, tier));
            }
        }

        // 排名後取前 N
        qualifiedAll.sort((a, b) -> b.momentumScore().compareTo(a.momentumScore()));
        List<MomentumPick> picks = qualifiedAll.stream().limit(maxPicks).toList();

        log.info("[Momentum] tradingDate={} scored={} basicQualified={} >=entryMin={} picked={}",
                tradingDate, scoredCandidates.size(), basicQualified, overEntryThreshold, picks.size());

        return new MomentumResultBundle(picks, observationMode, basicQualified, overEntryThreshold);
    }

    // ── 輔助資料（由 candidate payload + stock_evaluation 組成） ──────────────

    private record MomentumAuxData(
            Double todayChangePct,
            Double volumeRatioTo5MA,
            Boolean breakoutVolumeSpike,
            Boolean todayAboveOpen,
            List<String> claudeRiskFlags,
            Boolean codexVetoed
    ) {}

    private MomentumAuxData loadAuxData(LocalDate date, String symbol) {
        Double changePct = null;
        Boolean aboveOpen = null;
        Boolean breakoutSpike = null;

        CandidateStockEntity candidate = candidateStockRepository
                .findByTradingDateAndSymbol(date, symbol).orElse(null);
        if (candidate != null && candidate.getPayloadJson() != null) {
            try {
                JsonNode p = objectMapper.readTree(candidate.getPayloadJson());
                Double close = numOrNull(p, "close_price");
                Double prev  = numOrNull(p, "prev_close");
                Double open  = numOrNull(p, "open_price");
                if (close != null && prev != null && prev > 0) {
                    changePct = (close - prev) / prev * 100.0;
                }
                if (close != null && open != null) {
                    aboveOpen = close >= open;
                }
                // 突破量增：day_high 接近 close 且 volume_spike flag
                Boolean vs = boolOrNull(p, "volume_spike");
                if (Boolean.TRUE.equals(vs)) breakoutSpike = true;
            } catch (Exception e) {
                log.debug("[Momentum] parse payload failed for {}: {}", symbol, e.getMessage());
            }
        }

        List<String> riskFlags = List.of();
        Boolean codexVetoed = null; // v1 暫不接 AI task veto symbols
        StockEvaluationEntity eval = stockEvaluationRepository
                .findByTradingDateAndSymbol(date, symbol).orElse(null);
        if (eval != null && eval.getClaudeRiskFlags() != null) {
            try {
                riskFlags = objectMapper.readValue(eval.getClaudeRiskFlags(),
                        new TypeReference<List<String>>() {});
            } catch (Exception ignore) {}
        }
        return new MomentumAuxData(changePct, null, breakoutSpike, aboveOpen,
                riskFlags == null ? List.of() : riskFlags, codexVetoed);
    }

    private MomentumCandidateEngine.CandidateInput buildCandidateInput(
            FinalDecisionCandidateRequest c, MomentumAuxData aux) {
        return new MomentumCandidateEngine.CandidateInput(
                c.stockCode(),
                aux.todayChangePct(),
                null,                  // consecutiveUpDays — v1 無歷史
                null,                  // todayNewHigh20   — v1 無歷史
                aux.todayAboveOpen(),
                null, null, null,      // MA flags — v1 無歷史
                aux.volumeRatioTo5MA(),
                aux.breakoutVolumeSpike(),
                c.themeRank(),
                c.finalThemeScore(),
                c.claudeScore(),
                aux.claudeRiskFlags(),
                aux.codexVetoed()
        );
    }

    private VetoEngine.VetoInput buildVetoInput(
            FinalDecisionCandidateRequest c, MomentumAuxData aux) {
        return new VetoEngine.VetoInput(
                null,  // marketGrade — 在 evaluate() 入口已檢查，內部不再 veto（避免雙重）
                null,
                null,
                c.riskRewardRatio() == null ? null : BigDecimal.valueOf(c.riskRewardRatio()),
                c.includeInFinalPlan(),
                c.stopLossPrice() == null ? null : BigDecimal.valueOf(c.stopLossPrice()),
                c.valuationMode(),
                c.hasTheme(),
                c.themeRank(),
                c.finalThemeScore(),
                c.codexScore(),
                c.javaStructureScore(),
                c.claudeScore(),
                c.volumeSpike(),
                c.priceNotBreakHigh(),
                c.entryTooExtended(),
                aux.claudeRiskFlags(),
                aux.codexVetoed()
        );
    }

    private MomentumScoringEngine.MomentumInput buildScoringInput(
            FinalDecisionCandidateRequest c, MomentumAuxData aux) {
        return new MomentumScoringEngine.MomentumInput(
                c.stockCode(),
                aux.todayChangePct(), null, null,
                aux.volumeRatioTo5MA(),
                c.volumeSpike() != null && c.volumeSpike() && c.priceNotBreakHigh() != null
                        && c.priceNotBreakHigh(),  // 這就是爆量長黑
                c.themeRank(), c.finalThemeScore(),
                c.claudeScore(), c.codexScore(),
                aux.claudeRiskFlags(),
                null, null
        );
    }

    private MomentumPick buildPick(
            FinalDecisionCandidateRequest c, MomentumAuxData aux,
            MomentumScoringEngine.MomentumResult score,
            MomentumScoringEngine.MomentumTier tier
    ) {
        // 倉位倍數：基本 0.5；STRONG 可提升
        BigDecimal baseRatio = config.getDecimal("momentum.position_size_ratio", new BigDecimal("0.5"));
        BigDecimal strongRatio = config.getDecimal("momentum.strong_position_ratio", new BigDecimal("0.7"));
        BigDecimal positionMultiplier = tier == MomentumScoringEngine.MomentumTier.STRONG_ENTER
                ? strongRatio : baseRatio;

        // 停損：若原 setup 停損存在沿用；否則用 momentum.stop_loss_pct 從現價估
        BigDecimal stopLossPct = config.getDecimal("momentum.stop_loss_pct", new BigDecimal("-0.025"));
        Double stopLoss = c.stopLossPrice();
        // 若 stopLoss 是從 Setup 算的，對 Momentum 來說太鬆 — 改用 entry × (1 + stopLossPct)
        // 這裡簡單策略：以 close 為基準（aux 有 todayChangePct 但沒 close，交給下游 enrichment 補）
        // v1：若 setup 停損 > entry * 0.98，以 -2.5% 覆寫；其餘維持 setup 停損
        // ※ 這個 stopLossPrice 可能會被 FinalDecisionService 後續的 enrichWithSizing 再算，
        //   所以這裡只在 Momentum 情境下提前設定一個約束值。

        Double tp1 = c.takeProfit1();
        Double tp2 = c.takeProfit2();

        String rationale = String.format(
                "Momentum 追價（score=%s, tier=%s）：漲幅 %s%%，題材 rank=%s",
                score.momentumScore().toPlainString(),
                tier.name(),
                aux.todayChangePct() == null ? "?"
                        : BigDecimal.valueOf(aux.todayChangePct()).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                c.themeRank() == null ? "-" : c.themeRank()
        );

        FinalDecisionSelectedStockResponse selected = new FinalDecisionSelectedStockResponse(
                c.stockCode(), c.stockName(),
                "MOMENTUM",
                c.entryPriceZone(),
                stopLoss,
                tp1, tp2,
                c.riskRewardRatio(),
                rationale,
                null,                    // suggestedPositionSize 由 FinalDecisionService.enrichWithSizing 算
                positionMultiplier.doubleValue(),
                StrategyType.MOMENTUM_CHASE.name(),
                score.momentumScore().doubleValue()
        );
        return new MomentumPick(selected, score.momentumScore(), tier, score.details());
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    private Double numOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        if (!n.isNumber()) {
            try { return Double.parseDouble(n.asText()); }
            catch (NumberFormatException e) { return null; }
        }
        return n.asDouble();
    }

    private Boolean boolOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        return n.asBoolean();
    }
}
