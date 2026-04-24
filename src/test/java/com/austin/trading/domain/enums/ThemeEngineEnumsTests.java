package com.austin.trading.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2 Theme Engine PR1：5 個新 enum 的 parse 行為測試。
 *
 * <p>重點驗證：null / blank / 小寫 / 未定義值 → UNKNOWN（或 CONFLICT_REVIEW_REQUIRED），不拋錯。</p>
 */
class ThemeEngineEnumsTests {

    @Test
    void trendStage_parseExact() {
        assertThat(TrendStage.parseOrUnknown("EARLY")).isEqualTo(TrendStage.EARLY);
        assertThat(TrendStage.parseOrUnknown("MID")).isEqualTo(TrendStage.MID);
        assertThat(TrendStage.parseOrUnknown("LATE")).isEqualTo(TrendStage.LATE);
    }

    @Test
    void trendStage_parseLowercaseAndTrim() {
        assertThat(TrendStage.parseOrUnknown("  early ")).isEqualTo(TrendStage.EARLY);
        assertThat(TrendStage.parseOrUnknown("mid")).isEqualTo(TrendStage.MID);
    }

    @Test
    void trendStage_nullBlankUnknown_returnsUnknown() {
        assertThat(TrendStage.parseOrUnknown(null)).isEqualTo(TrendStage.UNKNOWN);
        assertThat(TrendStage.parseOrUnknown("")).isEqualTo(TrendStage.UNKNOWN);
        assertThat(TrendStage.parseOrUnknown("   ")).isEqualTo(TrendStage.UNKNOWN);
        assertThat(TrendStage.parseOrUnknown("FOO")).isEqualTo(TrendStage.UNKNOWN);
    }

    @Test
    void rotationSignal_allValues() {
        assertThat(RotationSignal.parseOrUnknown("IN")).isEqualTo(RotationSignal.IN);
        assertThat(RotationSignal.parseOrUnknown("OUT")).isEqualTo(RotationSignal.OUT);
        assertThat(RotationSignal.parseOrUnknown("NONE")).isEqualTo(RotationSignal.NONE);
        assertThat(RotationSignal.parseOrUnknown("rotate-in")).isEqualTo(RotationSignal.UNKNOWN);
        assertThat(RotationSignal.parseOrUnknown(null)).isEqualTo(RotationSignal.UNKNOWN);
    }

    @Test
    void crowdingRisk_allValues() {
        assertThat(CrowdingRisk.parseOrUnknown("LOW")).isEqualTo(CrowdingRisk.LOW);
        assertThat(CrowdingRisk.parseOrUnknown("MID")).isEqualTo(CrowdingRisk.MID);
        assertThat(CrowdingRisk.parseOrUnknown("HIGH")).isEqualTo(CrowdingRisk.HIGH);
        assertThat(CrowdingRisk.parseOrUnknown("extreme")).isEqualTo(CrowdingRisk.UNKNOWN);
        assertThat(CrowdingRisk.parseOrUnknown(null)).isEqualTo(CrowdingRisk.UNKNOWN);
    }

    @Test
    void themeRole_allValues() {
        assertThat(ThemeRole.parseOrUnknown("LEADER")).isEqualTo(ThemeRole.LEADER);
        assertThat(ThemeRole.parseOrUnknown("FOLLOWER")).isEqualTo(ThemeRole.FOLLOWER);
        assertThat(ThemeRole.parseOrUnknown("LAGGARD")).isEqualTo(ThemeRole.LAGGARD);
        assertThat(ThemeRole.parseOrUnknown("king")).isEqualTo(ThemeRole.UNKNOWN);
        assertThat(ThemeRole.parseOrUnknown(null)).isEqualTo(ThemeRole.UNKNOWN);
    }

    @Test
    void decisionDiffType_allSixValues() {
        assertThat(DecisionDiffType.parseOrConflict("SAME_BUY"))
                .isEqualTo(DecisionDiffType.SAME_BUY);
        assertThat(DecisionDiffType.parseOrConflict("SAME_WAIT"))
                .isEqualTo(DecisionDiffType.SAME_WAIT);
        assertThat(DecisionDiffType.parseOrConflict("LEGACY_BUY_THEME_BLOCK"))
                .isEqualTo(DecisionDiffType.LEGACY_BUY_THEME_BLOCK);
        assertThat(DecisionDiffType.parseOrConflict("LEGACY_WAIT_THEME_BUY"))
                .isEqualTo(DecisionDiffType.LEGACY_WAIT_THEME_BUY);
        assertThat(DecisionDiffType.parseOrConflict("BOTH_BLOCK"))
                .isEqualTo(DecisionDiffType.BOTH_BLOCK);
        assertThat(DecisionDiffType.parseOrConflict("CONFLICT_REVIEW_REQUIRED"))
                .isEqualTo(DecisionDiffType.CONFLICT_REVIEW_REQUIRED);
    }

    @Test
    void decisionDiffType_unknownFallsBackToConflict() {
        assertThat(DecisionDiffType.parseOrConflict(null))
                .isEqualTo(DecisionDiffType.CONFLICT_REVIEW_REQUIRED);
        assertThat(DecisionDiffType.parseOrConflict(""))
                .isEqualTo(DecisionDiffType.CONFLICT_REVIEW_REQUIRED);
        assertThat(DecisionDiffType.parseOrConflict("SAME_EXIT"))
                .isEqualTo(DecisionDiffType.CONFLICT_REVIEW_REQUIRED);
    }
}
