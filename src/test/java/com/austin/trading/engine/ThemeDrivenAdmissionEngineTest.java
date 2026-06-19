package com.austin.trading.engine;

import com.austin.trading.domain.enums.ThemeAdmissionShadowAction;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeDrivenAdmissionEngineTest {

    private final ThemeDrivenAdmissionEngine engine = new ThemeDrivenAdmissionEngine();

    @Test
    void leaderWithoutLimitRiskWouldAdmitCandidate() {
        var decision = engine.evaluate(signal("2330", "AI", "THEME_LEADER", "3.2", false, null));
        assertThat(decision.action()).isEqualTo(ThemeAdmissionShadowAction.WOULD_ADMIT_CANDIDATE);
        assertThat(decision.leaderLike()).isTrue();
        assertThat(decision.pullbackPlan()).isFalse();
    }

    @Test
    void secondLeaderWithLimitRiskWouldCreatePullbackPlan() {
        var decision = engine.evaluate(signal("2317", "AI", "SECOND_LEADER", "5.0", true, null));
        assertThat(decision.action()).isEqualTo(ThemeAdmissionShadowAction.WOULD_CREATE_PULLBACK_PLAN);
        assertThat(decision.watchlistLike()).isTrue();
        assertThat(decision.pullbackPlan()).isTrue();
    }

    @Test
    void strongFollowerWouldAdmitWatchlistAndBadDataRejects() {
        assertThat(engine.evaluate(signal("3008", "光學", "FOLLOWER", "7.1", false, null)).action())
                .isEqualTo(ThemeAdmissionShadowAction.WOULD_ADMIT_WATCHLIST);
        assertThat(engine.evaluate(signal("", "光學", "THEME_LEADER", "1.0", false, null)).action())
                .isEqualTo(ThemeAdmissionShadowAction.REJECT);
        assertThat(engine.evaluate(signal("3008", "光學", "THEME_LEADER", "1.0", false, "SUSPENDED")).action())
                .isEqualTo(ThemeAdmissionShadowAction.REJECT);
    }

    private HotGroupStockSignalEntity signal(String symbol, String theme, String role, String changePct,
                                             boolean limitRisk, String tradabilityTag) {
        HotGroupStockSignalEntity e = new HotGroupStockSignalEntity();
        e.setTradingDate(LocalDate.of(2026, 6, 19));
        e.setSymbol(symbol);
        e.setThemeTag(theme);
        e.setRole(role);
        e.setChangePct(new BigDecimal(changePct));
        e.setLimitRisk(limitRisk);
        e.setTradabilityTag(tradabilityTag);
        return e;
    }
}
