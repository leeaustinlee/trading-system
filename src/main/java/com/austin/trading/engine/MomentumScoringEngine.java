package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.3 Momentum Chase 評分引擎。
 * <p>
 * 獨立於 Setup Scoring，不使用 A+ 門檻。滿分 10，進場門檻 {@code momentum.entry_score_min}（預設 7.5）。
 * 子分數：priceMomentum(0-3) + volume(0-2) + theme(0-2) + aiSupport(0-2) + structure(0-1) - vetoPenalty。
 * </p>
 *
 * <h3>資料來源</h3>
 * v1 先用輕量版欄位（無歷史 OHLC 時仍可計分）：
 * <ul>
 *   <li>{@code todayChangePct}：當日漲幅 % — 由 candidate payload 或 live quote 提供</li>
 *   <li>{@code volumeRatioTo5MA}：今日量 / 5 日均量 — 若無歷史回 null，volume 子分給中性 0.5</li>
 *   <li>{@code consecutiveUpDays}：連續上漲日數 — 無歷史回 0</li>
 *   <li>{@code aboveMa5}, {@code ma5Turning}：均線結構 — 無歷史回 null，structure 子分給中性 0.5</li>
 * </ul>
 */
@Component
public class MomentumScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(MomentumScoringEngine.class);

    private final ScoreConfigService config;

    public MomentumScoringEngine(ScoreConfigService config) {
        this.config = config;
    }

    /** 輸入：每檔股票評分所需資料。欄位 null 時該項採中性分。 */
    public record MomentumInput(
            String symbol,
            // 價格動能
            Double todayChangePct,        // 今日漲幅 %（e.g. 5.3）
            Integer consecutiveUpDays,    // 連續上漲日數（0~N）
            Boolean todayNewHigh20,       // 今日是否創 20 日新高（無歷史→null）
            // 成交量
            Double volumeRatioTo5MA,      // 今日量 / 5MA；無歷史→null
            Boolean volumeSpikeLongBlack, // 爆量長黑？
            // 題材
            Integer themeRank,            // 1~N；null→無題材
            BigDecimal finalThemeScore,   // 題材最終分 0-10
            // AI 評分
            BigDecimal claudeScore,       // 0-10
            BigDecimal codexScore,        // 0-10
            List<String> claudeRiskFlags,
            // 均線結構
            Boolean aboveMa5,             // 收盤 > 5MA？
            Boolean ma5Turning            // 5MA 上彎？
    ) {}

    /** 輸出：總分 + 子分數明細。 */
    public record MomentumResult(
            BigDecimal momentumScore,         // 0-10（已扣 vetoPenalty）
            BigDecimal priceMomentumScore,    // 0-3
            BigDecimal volumeScore,           // 0-2
            BigDecimal themeScore,            // 0-2
            BigDecimal aiSupportScore,        // 0-2
            BigDecimal structureScore,        // 0-1
            BigDecimal vetoPenalty,           // 已從總分扣除的懲罰
            Map<String, Object> details       // 供 debug / stock_evaluation 存 JSON
    ) {}

    public MomentumResult compute(MomentumInput in, BigDecimal vetoPenalty) {
        BigDecimal priceMomentum = scorePriceMomentum(in);
        BigDecimal volume        = scoreVolume(in);
        BigDecimal theme         = scoreTheme(in);
        BigDecimal ai            = scoreAiSupport(in);
        BigDecimal structure     = scoreStructure(in);

        BigDecimal raw = priceMomentum.add(volume).add(theme).add(ai).add(structure);
        BigDecimal penalty = vetoPenalty == null ? BigDecimal.ZERO : vetoPenalty;
        BigDecimal finalScore = raw.subtract(penalty).max(BigDecimal.ZERO);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("symbol",              in.symbol());
        details.put("priceMomentumScore",  priceMomentum);
        details.put("volumeScore",         volume);
        details.put("themeScore",          theme);
        details.put("aiSupportScore",      ai);
        details.put("structureScore",      structure);
        details.put("rawScore",            raw);
        details.put("vetoPenalty",         penalty);
        details.put("finalScore",          finalScore);

        log.debug("[MomentumScoring] {} raw={} penalty={} final={}",
                in.symbol(), raw, penalty, finalScore);
        return new MomentumResult(
                finalScore.setScale(2, RoundingMode.HALF_UP),
                priceMomentum, volume, theme, ai, structure, penalty, details);
    }

    /** 便利方法：vetoPenalty=0 的純計算。 */
    public MomentumResult compute(MomentumInput in) {
        return compute(in, BigDecimal.ZERO);
    }

    // ── 子分數 ─────────────────────────────────────────────────────────────

    /** priceMomentumScore 0~3：日漲幅 + 連續上漲 + 20 日新高。 */
    private BigDecimal scorePriceMomentum(MomentumInput in) {
        double score = 0.0;
        Double pct = in.todayChangePct();
        if (pct != null) {
            if (pct >= 5.0)      score += 3.0;
            else if (pct >= 3.0) score += 2.0;
            else if (pct >= 1.5) score += 1.0;
            // 負漲不加分（也不扣）
        }
        Integer consec = in.consecutiveUpDays();
        if (consec != null && consec >= 2) {
            score += 0.5;
        }
        if (Boolean.TRUE.equals(in.todayNewHigh20())) {
            score += 0.5;
        }
        if (score > 3.0) score = 3.0;
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /** volumeScore 0~2：今日量/5MA；爆量長黑扣分。 */
    private BigDecimal scoreVolume(MomentumInput in) {
        double score;
        Double ratio = in.volumeRatioTo5MA();
        if (ratio == null) {
            score = 0.5; // 中性（無歷史時）
        } else if (ratio >= 2.0) {
            score = 2.0;
        } else if (ratio >= 1.5) {
            score = 1.5;
        } else if (ratio >= 1.0) {
            score = 1.0;
        } else {
            score = 0.0;
        }
        if (Boolean.TRUE.equals(in.volumeSpikeLongBlack())) {
            score -= 1.0; // 爆量長黑嚴重警示
        }
        if (score < 0) score = 0;
        if (score > 2) score = 2;
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /** themeScore 0~2：themeRank 或 finalThemeScore。 */
    private BigDecimal scoreTheme(MomentumInput in) {
        double score = 0.0;
        Integer rank = in.themeRank();
        if (rank != null) {
            if (rank == 1)       score = 2.0;
            else if (rank == 2)  score = 1.5;
        }
        if (score == 0.0 && in.finalThemeScore() != null) {
            double t = in.finalThemeScore().doubleValue();
            if (t >= 7.0)      score = 1.0;
            else if (t >= 5.0) score = 0.5;
        }
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /** aiSupportScore 0~2：claude + codex 各 1 分。 */
    private BigDecimal scoreAiSupport(MomentumInput in) {
        double score = 0.0;
        if (in.claudeScore() != null && in.claudeScore().doubleValue() >= 7.0) score += 1.0;
        if (in.codexScore()  != null && in.codexScore().doubleValue()  >= 7.0) score += 1.0;
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /** structureScore 0~1：站上 5MA + 5MA 上彎。 */
    private BigDecimal scoreStructure(MomentumInput in) {
        // 無歷史時兩項都 null，給中性 0.5
        if (in.aboveMa5() == null && in.ma5Turning() == null) {
            return BigDecimal.valueOf(0.5);
        }
        double score = 0.0;
        if (Boolean.TRUE.equals(in.aboveMa5()))   score += 0.5;
        if (Boolean.TRUE.equals(in.ma5Turning())) score += 0.5;
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    // ── 決策層級 ────────────────────────────────────────────────────────────

    public enum MomentumTier { BELOW_WATCH, WATCH, ENTER, STRONG_ENTER }

    public MomentumTier classify(BigDecimal momentumScore) {
        if (momentumScore == null) return MomentumTier.BELOW_WATCH;
        BigDecimal watchMin  = config.getDecimal("momentum.watch_score_min",  new BigDecimal("5.0"));
        BigDecimal entryMin  = config.getDecimal("momentum.entry_score_min",  new BigDecimal("7.5"));
        BigDecimal strongMin = config.getDecimal("momentum.strong_score_min", new BigDecimal("9.0"));
        if (momentumScore.compareTo(strongMin) >= 0) return MomentumTier.STRONG_ENTER;
        if (momentumScore.compareTo(entryMin)  >= 0) return MomentumTier.ENTER;
        if (momentumScore.compareTo(watchMin)  >= 0) return MomentumTier.WATCH;
        return MomentumTier.BELOW_WATCH;
    }
}
