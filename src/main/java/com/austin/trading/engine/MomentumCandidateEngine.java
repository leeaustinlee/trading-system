package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v2.3 Momentum Chase 候選篩選引擎。
 * <p>
 * 5 條基本條件；至少符合 {@code momentum.basic_conditions_min}（預設 3）才視為 Momentum 候選。
 * </p>
 *
 * <h3>5 條基本條件</h3>
 * <ol>
 *   <li><b>priceMomentum</b> — 日漲 ≥ 3%、或 20 日新高、或連續 2~3 日上漲</li>
 *   <li><b>ma</b> — 站上 5MA、5MA &gt; 10MA、5MA 上彎（至少 2 項為真；無歷史不算）</li>
 *   <li><b>volume</b> — 今日量 &gt; 5MA × 1.5，或突破量增</li>
 *   <li><b>theme</b> — themeRank ≤ 2，或 finalThemeScore ≥ 7</li>
 *   <li><b>aiSupport</b> — Claude 無重大 riskFlags、Codex 未 veto</li>
 * </ol>
 */
@Component
public class MomentumCandidateEngine {

    private static final Logger log = LoggerFactory.getLogger(MomentumCandidateEngine.class);

    private final ScoreConfigService config;

    public MomentumCandidateEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record CandidateInput(
            String symbol,
            // priceMomentum
            Double todayChangePct,
            Integer consecutiveUpDays,
            Boolean todayNewHigh20,
            Boolean todayAboveOpen,        // 今日收盤 > 開盤（未跌破開盤）
            // ma
            Boolean aboveMa5,
            Boolean ma5OverMa10,
            Boolean ma5Turning,
            // volume
            Double volumeRatioTo5MA,
            Boolean breakoutVolumeSpike,
            // theme
            Integer themeRank,
            BigDecimal finalThemeScore,
            // aiSupport
            BigDecimal claudeScore,
            List<String> claudeRiskFlags,
            Boolean codexVetoed
    ) {}

    public record CandidateDecision(
            boolean isMomentumCandidate,
            boolean aiStronglyNegative,       // 觸發 AI_STRONG_NEGATIVE 直接排除（即使其他條件都對）
            int matchedConditionsCount,
            Map<String, Boolean> matchedFlags // 每條基本條件的結果（供 debug / DB 存 json）
    ) {}

    public CandidateDecision evaluate(CandidateInput in) {
        // 硬性排除：AI 強烈負評
        boolean negative = isAiStronglyNegative(in);

        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("priceMomentum", hasPriceMomentum(in));
        flags.put("ma",            hasMaStructure(in));
        flags.put("volume",        hasVolume(in));
        flags.put("theme",         hasTheme(in));
        flags.put("aiSupport",     hasAiSupport(in) && !negative);

        int matched = (int) flags.values().stream().filter(Boolean::booleanValue).count();
        int min = config.getInt("momentum.basic_conditions_min", 3);

        // AI 強烈負評：即使條件數滿足也排除
        boolean qualified = !negative && matched >= min;

        log.debug("[MomentumCandidate] {} matched={}/{} negative={} qualified={} flags={}",
                in.symbol(), matched, min, negative, qualified, flags);
        return new CandidateDecision(qualified, negative, matched, flags);
    }

    // ── 5 條基本條件 ────────────────────────────────────────────────────────

    private boolean hasPriceMomentum(CandidateInput in) {
        if (in.todayChangePct() != null && in.todayChangePct() >= 3.0) return true;
        if (Boolean.TRUE.equals(in.todayNewHigh20())) return true;
        if (in.consecutiveUpDays() != null && in.consecutiveUpDays() >= 2
                && Boolean.TRUE.equals(in.todayAboveOpen())) return true;
        return false;
    }

    private boolean hasMaStructure(CandidateInput in) {
        // 三個 MA flag 都 null 代表無歷史 → 中性處理：給 TRUE 讓它不會卡 candidate（但 scoring 子分給中性 0.5）
        if (in.aboveMa5() == null && in.ma5OverMa10() == null && in.ma5Turning() == null) {
            return true;
        }
        int yes = 0;
        if (Boolean.TRUE.equals(in.aboveMa5()))     yes++;
        if (Boolean.TRUE.equals(in.ma5OverMa10()))  yes++;
        if (Boolean.TRUE.equals(in.ma5Turning()))   yes++;
        return yes >= 2;
    }

    private boolean hasVolume(CandidateInput in) {
        if (in.volumeRatioTo5MA() != null && in.volumeRatioTo5MA() >= 1.5) return true;
        if (Boolean.TRUE.equals(in.breakoutVolumeSpike())) return true;
        return false;
    }

    private boolean hasTheme(CandidateInput in) {
        if (in.themeRank() != null && in.themeRank() <= 2) return true;
        if (in.finalThemeScore() != null && in.finalThemeScore().doubleValue() >= 7.0) return true;
        return false;
    }

    private boolean hasAiSupport(CandidateInput in) {
        if (Boolean.TRUE.equals(in.codexVetoed())) return false;
        // claudeScore 可為 null（尚未研究），視為中性 TRUE
        if (in.claudeScore() != null && in.claudeScore().doubleValue() < 4.0) return false;
        return true;
    }

    // ── 強烈負評判定 ────────────────────────────────────────────────────────

    public boolean isAiStronglyNegative(CandidateInput in) {
        if (Boolean.TRUE.equals(in.codexVetoed())) return true;

        BigDecimal claudeMin = config.getDecimal("momentum.veto.claude_score_min", new BigDecimal("4.0"));
        if (in.claudeScore() != null && in.claudeScore().compareTo(claudeMin) < 0) return true;

        List<String> flags = in.claudeRiskFlags();
        if (flags != null && !flags.isEmpty()) {
            String hardCsv = config.getString("momentum.veto.risk_flag_hard",
                    "LIQUIDITY_TRAP,EARNINGS_MISS,INSIDER_SELLING,VOLUME_SPIKE_LONG_BLACK,SUSPENDED_WARN");
            Set<String> hardSet = new HashSet<>(Arrays.asList(hardCsv.split(",")));
            for (String f : flags) {
                if (f != null && hardSet.contains(f.trim())) return true;
            }
        }
        return false;
    }
}
