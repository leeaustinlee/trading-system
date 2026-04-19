package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 單筆交易檢討引擎。
 *
 * <p>分析已關閉交易的進場/出場品質，輸出 review grade + tag + strengths/weaknesses。</p>
 */
@Component
public class TradeReviewEngine {

    private final ScoreConfigService config;

    public TradeReviewEngine(ScoreConfigService config) {
        this.config = config;
    }

    // ── Enums ──────────────────────────────────────────────────────────────

    public enum ReviewTag {
        GOOD_ENTRY_GOOD_EXIT,
        GOOD_ENTRY_EARLY_EXIT,
        GOOD_ENTRY_LATE_EXIT,
        CHASED_TOO_HIGH,
        BOUGHT_WEAK_THEME,
        FAILED_BREAKOUT_ENTRY,
        HELD_TOO_LONG,
        STOP_TOO_WIDE,
        STOP_TOO_TIGHT,
        SHOULD_NOT_ENTER,
        STRONG_HOLD_WORKED,
        STRONG_HOLD_BUT_GAVE_BACK_PROFIT
    }

    public enum MarketCondition { BULL, RANGE, BEAR }

    // ── Input / Output ─────────────────────────────────────────────────────

    public record TradeReviewInput(
            String symbol,
            BigDecimal entryPrice, BigDecimal exitPrice,
            BigDecimal pnlPct, int holdingDays,
            BigDecimal mfePct, BigDecimal maePct,
            String entryReason, String exitReason,
            BigDecimal finalRankScore, BigDecimal javaScore, BigDecimal claudeScore,
            Integer themeRank, BigDecimal finalThemeScore,
            boolean wasExtended, int consecutiveStrongDays,
            String watchlistStatus, String marketGrade,
            BigDecimal originalStopLoss, BigDecimal trailingStopAtExit,
            boolean wasFailedBreakout, boolean wasWeakTheme,
            MarketCondition marketCondition
    ) {}

    public record TradeReviewResult(
            String reviewGrade,
            String primaryTag,
            List<String> secondaryTags,
            List<String> strengths,
            List<String> weaknesses,
            List<String> improvementSuggestions,
            String aiSummary,
            MarketCondition marketCondition
    ) {}

    // ── 主要邏輯 ──────────────────────────────────────────────────────────

    public TradeReviewResult evaluate(TradeReviewInput in) {
        BigDecimal pnl = in.pnlPct() != null ? in.pnlPct() : BigDecimal.ZERO;
        BigDecimal mfe = in.mfePct() != null ? in.mfePct() : BigDecimal.ZERO;
        BigDecimal mae = in.maePct() != null ? in.maePct() : BigDecimal.ZERO;
        boolean isWin = pnl.signum() > 0;

        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        List<String> secondaryTags = new ArrayList<>();

        // ─ 判斷 primary tag ──────────────────────────────────────────────
        ReviewTag primary = determinePrimaryTag(in, pnl, mfe, mae, isWin,
                strengths, weaknesses, suggestions, secondaryTags);

        // ─ 判斷 grade ────────────────────────────────────────────────────
        String grade = determineGrade(primary, pnl, mfe, isWin);

        // ─ 產生文字摘要 ──────────────────────────────────────────────────
        String summary = buildSummary(in, primary, grade, pnl);

        return new TradeReviewResult(
                grade, primary.name(), secondaryTags,
                strengths, weaknesses, suggestions, summary,
                in.marketCondition());
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private ReviewTag determinePrimaryTag(TradeReviewInput in, BigDecimal pnl,
                                           BigDecimal mfe, BigDecimal mae, boolean isWin,
                                           List<String> strengths, List<String> weaknesses,
                                           List<String> suggestions, List<String> secondaryTags) {

        BigDecimal chasedPct = config.getDecimal("review.chased_high_pct", new BigDecimal("3.0"));
        BigDecimal givebackPct = config.getDecimal("review.profit_giveback_pct", new BigDecimal("50.0"));
        int tooLongDays = config.getInt("review.held_too_long_days", 12);

        // 假突破進場
        if (in.wasFailedBreakout()) {
            weaknesses.add("進場後出現假突破型態");
            suggestions.add("下次等突破確認後再進場");
            return ReviewTag.FAILED_BREAKOUT_ENTRY;
        }

        // 追高
        if (in.wasExtended() && !isWin) {
            weaknesses.add("進場時股價已延伸過頭");
            suggestions.add("避免在延伸位置進場，等回測再買");
            return ReviewTag.CHASED_TOO_HIGH;
        }

        // 弱題材
        if (in.wasWeakTheme() && !isWin) {
            weaknesses.add("進場時題材已轉弱");
            suggestions.add("優先選擇題材排名前段的標的");
            return ReviewTag.BOUGHT_WEAK_THEME;
        }

        // 不該進場（多重負面條件）
        if (in.wasFailedBreakout() || (in.wasExtended() && in.wasWeakTheme())) {
            weaknesses.add("多重負面條件同時存在");
            return ReviewTag.SHOULD_NOT_ENTER;
        }

        // 持有過久
        if (in.holdingDays() >= tooLongDays && pnl.compareTo(new BigDecimal("3")) < 0) {
            weaknesses.add("持有 " + in.holdingDays() + " 天但報酬不足");
            suggestions.add("考慮縮短持有上限或提早移動停利");
            secondaryTags.add(ReviewTag.HELD_TOO_LONG.name());
            if (!isWin) return ReviewTag.HELD_TOO_LONG;
        }

        // 獲利回吐
        if (isWin && mfe.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal givebackRatio = mfe.subtract(pnl).divide(mfe, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            if (givebackRatio.compareTo(givebackPct) >= 0) {
                weaknesses.add("最大獲利 " + mfe.toPlainString() + "% 回吐至 " + pnl.toPlainString() + "%");
                suggestions.add("在獲利高點時更積極移動停利");
                secondaryTags.add(ReviewTag.STRONG_HOLD_BUT_GAVE_BACK_PROFIT.name());
                return ReviewTag.STRONG_HOLD_BUT_GAVE_BACK_PROFIT;
            }
        }

        // 停損太寬（mae 很大但最終停損出場）
        if (!isWin && mae.abs().compareTo(new BigDecimal("8")) >= 0) {
            weaknesses.add("最大虧損達 " + mae.toPlainString() + "%，停損設定可能太寬");
            suggestions.add("收緊停損或更快啟動 trailing stop");
            secondaryTags.add(ReviewTag.STOP_TOO_WIDE.name());
        }

        // 停損太緊（獲利交易但 mae 較小就被洗出）
        if (!isWin && mae.abs().compareTo(new BigDecimal("2")) < 0 && mfe.compareTo(new BigDecimal("3")) >= 0) {
            weaknesses.add("曾獲利 " + mfe.toPlainString() + "% 但因小幅回檔被洗出");
            suggestions.add("停損可略為放寬或等確認破線再出場");
            return ReviewTag.STOP_TOO_TIGHT;
        }

        // 好進場好出場
        if (isWin && pnl.compareTo(new BigDecimal("5")) >= 0) {
            strengths.add("獲利 " + pnl.toPlainString() + "%，執行到位");
            if (in.consecutiveStrongDays() >= 2) strengths.add("從 Watchlist 連續觀察後進場");
            return ReviewTag.GOOD_ENTRY_GOOD_EXIT;
        }

        // 好進場但提早出場
        if (isWin && mfe.compareTo(pnl.multiply(new BigDecimal("2"))) >= 0) {
            weaknesses.add("最大獲利達 " + mfe.toPlainString() + "% 但只拿到 " + pnl.toPlainString() + "%");
            suggestions.add("持有耐心不足，考慮分批停利");
            return ReviewTag.GOOD_ENTRY_EARLY_EXIT;
        }

        // 強勢持有成功
        if (isWin) {
            strengths.add("獲利出場，交易正確");
            return ReviewTag.STRONG_HOLD_WORKED;
        }

        // 虧損但進場邏輯沒問題
        if (!isWin && in.finalRankScore() != null && in.finalRankScore().compareTo(new BigDecimal("8")) >= 0) {
            strengths.add("進場邏輯正確（分數 " + in.finalRankScore().toPlainString() + "）");
            weaknesses.add("虧損 " + pnl.toPlainString() + "%，市場環境不配合");
            return ReviewTag.GOOD_ENTRY_LATE_EXIT;
        }

        // 預設
        if (!isWin) {
            weaknesses.add("虧損 " + pnl.toPlainString() + "%");
            return ReviewTag.SHOULD_NOT_ENTER;
        }

        return ReviewTag.STRONG_HOLD_WORKED;
    }

    private String determineGrade(ReviewTag primary, BigDecimal pnl, BigDecimal mfe, boolean isWin) {
        return switch (primary) {
            case GOOD_ENTRY_GOOD_EXIT, STRONG_HOLD_WORKED -> "A";
            case GOOD_ENTRY_EARLY_EXIT, GOOD_ENTRY_LATE_EXIT -> "B";
            case STRONG_HOLD_BUT_GAVE_BACK_PROFIT, HELD_TOO_LONG, STOP_TOO_WIDE, STOP_TOO_TIGHT -> "C";
            case CHASED_TOO_HIGH, BOUGHT_WEAK_THEME, FAILED_BREAKOUT_ENTRY, SHOULD_NOT_ENTER -> "D";
        };
    }

    private String buildSummary(TradeReviewInput in, ReviewTag tag, String grade, BigDecimal pnl) {
        String mc = in.marketCondition() != null ? in.marketCondition().name() : "N/A";
        return String.format("[%s] %s %s%% | %s | 持有%d天 | 市場:%s | %s",
                grade, in.symbol(),
                pnl.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                tag.name(), in.holdingDays(), mc,
                pnl.signum() >= 0 ? "獲利" : "虧損");
    }
}
