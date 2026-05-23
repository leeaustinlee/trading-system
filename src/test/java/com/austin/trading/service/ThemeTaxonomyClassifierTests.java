package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeTaxonomyClassifierTests {

    @Test
    void classifiesPassiveComponentsAndMlccAsElectronicsComponentsNotSemiconductorOrOther() {
        assertThat(ThemeTaxonomyClassifier.classify("被動元件/MLCC"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.classify("鋁電容報價上漲"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestCategoryForGenericOther("2327", "國巨*"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestCategoryForGenericOther("2492", "華新科"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestCategoryForGenericOther("3026", "禾伸堂"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestCategoryForGenericOther("3090", "日電貿"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestCategoryForGenericOther("6173", "信昌電"))
                .isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
    }

    @Test
    void suggestsPassiveComponentSubThemesForKnownStocks() {
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("2327", "國巨*"))
                .isEqualTo(ThemeTaxonomyClassifier.MLCC);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("2492", "華新科"))
                .isEqualTo(ThemeTaxonomyClassifier.MLCC);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("2375", "凱美"))
                .isEqualTo(ThemeTaxonomyClassifier.ALUMINUM_CAPACITOR);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("2472", "立隆電"))
                .isEqualTo(ThemeTaxonomyClassifier.ALUMINUM_CAPACITOR);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("8042", "金山電"))
                .isEqualTo(ThemeTaxonomyClassifier.ALUMINUM_CAPACITOR);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("3236", "千如"))
                .isEqualTo(ThemeTaxonomyClassifier.PASSIVE_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("3068", "美磊"))
                .isEqualTo(ThemeTaxonomyClassifier.PASSIVE_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("2456", "奇力新"))
                .isEqualTo(ThemeTaxonomyClassifier.PASSIVE_COMPONENTS);
        assertThat(ThemeTaxonomyClassifier.suggestSubThemeForGenericOther("3357", "臺慶科"))
                .isEqualTo(ThemeTaxonomyClassifier.PASSIVE_COMPONENTS);
    }
}
