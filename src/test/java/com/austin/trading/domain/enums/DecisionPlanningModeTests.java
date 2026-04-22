package com.austin.trading.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPlanningModeTests {

    @Test
    void intradayTaskTypes_mapToIntradayEntry() {
        assertThat(DecisionPlanningMode.fromTaskType("PREMARKET"))
                .isEqualTo(DecisionPlanningMode.INTRADAY_ENTRY);
        assertThat(DecisionPlanningMode.fromTaskType("OPENING"))
                .isEqualTo(DecisionPlanningMode.INTRADAY_ENTRY);
        assertThat(DecisionPlanningMode.fromTaskType("MIDDAY"))
                .isEqualTo(DecisionPlanningMode.INTRADAY_ENTRY);
    }

    @Test
    void postmarketTaskTypes_mapToPostclosePlanning() {
        assertThat(DecisionPlanningMode.fromTaskType("POSTMARKET"))
                .isEqualTo(DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
        assertThat(DecisionPlanningMode.fromTaskType("T86_TOMORROW"))
                .isEqualTo(DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
    }

    @Test
    void nullOrUnknown_fallbackToIntradayEntry() {
        assertThat(DecisionPlanningMode.fromTaskType(null))
                .isEqualTo(DecisionPlanningMode.INTRADAY_ENTRY);
        assertThat(DecisionPlanningMode.fromTaskType("UNKNOWN_TYPE"))
                .isEqualTo(DecisionPlanningMode.INTRADAY_ENTRY);
    }

    @Test
    void caseInsensitive() {
        assertThat(DecisionPlanningMode.fromTaskType("t86_tomorrow"))
                .isEqualTo(DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
        assertThat(DecisionPlanningMode.fromTaskType("Postmarket"))
                .isEqualTo(DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
    }

    @Test
    void isPostClosePlanning_flag() {
        assertThat(DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN.isPostClosePlanning()).isTrue();
        assertThat(DecisionPlanningMode.INTRADAY_ENTRY.isPostClosePlanning()).isFalse();
    }
}
