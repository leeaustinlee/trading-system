package com.austin.trading.service;

import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeSnapshotValidationServiceTests {

    private final ThemeSnapshotValidationService svc = new ThemeSnapshotValidationService();

    @Test
    void validSnapshot_passes() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.of(2026, 4, 24, 9, 30, 0, 0, ZoneOffset.ofHours(8)),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(theme("AI_SERVER", "MID", "IN", "MID"))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isTrue();
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void nullSnapshot_invalid() {
        ThemeSnapshotValidationService.Result r = svc.validate(null);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).contains("snapshot=null");
    }

    @Test
    void missingGeneratedAt_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                null,
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(theme("AI_SERVER", "MID", "IN", "MID"))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("generated_at"));
    }

    @Test
    void marketRegimeFieldsMissing_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime(null, null, null),
                List.of(theme("AI_SERVER", "MID", "IN", "MID"))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("regime_type"))
                              .anyMatch(s -> s.contains("risk_multiplier"))
                              .anyMatch(s -> s.contains("trade_allowed"));
    }

    @Test
    void themeStrengthOutOfRange_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("11.5"),  // out of [0,10]
                        "MID", "IN",
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "MID",
                        List.of("NEWS"),
                        new BigDecimal("0.8"),
                        null,
                        List.of()
                ))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("theme_strength") && s.contains("out of"));
    }

    @Test
    void invalidEnumValues_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.0"),
                        "SUPER_EARLY",     // not allowed
                        "MAYBE",           // not allowed
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "EXTREME",         // not allowed
                        List.of("NEWS"),
                        new BigDecimal("0.8"),
                        null, List.of()
                ))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors())
                .anyMatch(s -> s.contains("trend_stage"))
                .anyMatch(s -> s.contains("rotation_signal"))
                .anyMatch(s -> s.contains("crowding_risk"));
    }

    @Test
    void evidenceSourcesEmpty_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.0"),
                        "MID", "IN",
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "MID",
                        List.of(),         // empty
                        new BigDecimal("0.8"),
                        null, List.of()
                ))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("evidence_sources must be non-empty"));
    }

    @Test
    void evidenceSourceNotInAllowed_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.0"),
                        "MID", "IN",
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "MID",
                        List.of("RUMOR_BOARD"),     // not in allowed set
                        new BigDecimal("0.8"),
                        null, List.of()
                ))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("RUMOR_BOARD"));
    }

    @Test
    void confidenceOutOfRange_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.0"),
                        "MID", "IN",
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "MID",
                        List.of("NEWS"),
                        new BigDecimal("1.5"),        // > 1.0
                        null, List.of()
                ))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("confidence"));
    }

    @Test
    void candidateWithBadRoleHint_invalid() {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.0"),
                        "MID", "IN",
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "MID",
                        List.of("NEWS"),
                        new BigDecimal("0.8"),
                        null,
                        List.of(new ThemeSnapshotV2Dto.ThemeCandidate(
                                "2454", "KING", new BigDecimal("0.9")))
                ))
        );
        ThemeSnapshotValidationService.Result r = svc.validate(snap);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(s -> s.contains("role_hint") && s.contains("KING"));
    }

    // helper
    private ThemeSnapshotV2Dto.Theme theme(String tag, String stage, String rot, String crowd) {
        return new ThemeSnapshotV2Dto.Theme(
                tag,
                new BigDecimal("7.3"),
                stage, rot,
                new BigDecimal("7.0"), new BigDecimal("7.0"),
                crowd,
                List.of("NEWS"),
                new BigDecimal("0.82"),
                null,
                List.of(new ThemeSnapshotV2Dto.ThemeCandidate("2454", "LEADER", new BigDecimal("0.9")))
        );
    }
}
