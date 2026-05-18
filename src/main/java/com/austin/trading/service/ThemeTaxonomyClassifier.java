package com.austin.trading.service;

import java.util.Locale;

/**
 * Canonical theme taxonomy classifier for mapping-quality cleanup.
 *
 * This is deliberately a data-quality helper: categories produced here are for
 * taxonomy / observability / snapshot labels only and must not be treated as a
 * BUY/SELL/FinalDecision signal.
 */
public final class ThemeTaxonomyClassifier {

    public static final String AI_COMPUTE = "AI_COMPUTE";
    public static final String SEMICONDUCTOR = "SEMICONDUCTOR";
    public static final String PCB = "PCB";
    public static final String MEMORY = "MEMORY";
    public static final String COOLING = "COOLING";
    public static final String COMMUNICATION = "COMMUNICATION";
    public static final String ROBOTICS = "ROBOTICS";
    public static final String DISPLAY = "DISPLAY";
    public static final String FINANCIAL = "FINANCIAL";
    public static final String MATERIALS = "MATERIALS";
    public static final String DEFENSE = "DEFENSE";
    public static final String BIOTECH = "BIOTECH";
    public static final String OTHER = "OTHER";
    public static final String UNKNOWN = "UNKNOWN";

    private ThemeTaxonomyClassifier() {
    }

    public static final String LEGACY_AI_CHIP_SEED_SOURCE = "legacy-ai-chip-seed";
    public static final String LEGACY_THEME_MAPPING_SOURCE = "legacy-theme-mapping";

    public static String classify(String themeTag) {
        if (!hasText(themeTag)) return UNKNOWN;
        String tag = themeTag.trim();
        String upper = tag.toUpperCase(Locale.ROOT);

        if (upper.startsWith("AI_CHIP") || containsAny(tag, "AI伺服器", "AI算力", "GB200", "伺服器", "電腦週邊")) {
            return AI_COMPUTE;
        }
        if (containsAny(tag, "PCB", "載板", "材料", "銅箔", "CCL", "ABF")) {
            return PCB;
        }
        if (containsAny(tag, "記憶體", "儲存", "DRAM", "NAND", "SSD")) {
            return MEMORY;
        }
        if (containsAny(tag, "半導體", "IC", "晶圓", "封測", "ASIC")) {
            return SEMICONDUCTOR;
        }
        if (containsAny(tag, "散熱", "機構", "水冷", "熱管", "風扇")) {
            return COOLING;
        }
        if (containsAny(tag, "網通", "通訊", "5G", "交換器", "光通訊")) {
            return COMMUNICATION;
        }
        if (containsAny(tag, "機器人", "自動化")) {
            return ROBOTICS;
        }
        if (containsAny(tag, "光電", "面板", "MiniLED", "MicroLED")) {
            return DISPLAY;
        }
        if (containsAny(tag, "金融", "銀行", "保險", "證券")) {
            return FINANCIAL;
        }
        if (containsAny(tag, "玻纖", "玻璃", "原物料", "化工", "塑化")) {
            return MATERIALS;
        }
        if (containsAny(tag, "軍工", "航太", "無人機")) {
            return DEFENSE;
        }
        if (containsAny(tag, "生技", "醫療", "製藥", "藥")) {
            return BIOTECH;
        }
        if (containsAny(tag, "其他", "強勢股")) {
            return OTHER;
        }
        return OTHER;
    }

    /**
     * Deterministic provenance label for legacy mapping rows whose source was blank.
     * This is a data-lineage label only; it does not imply live trading confidence.
     */
    public static String inferLegacySource(String themeTag) {
        if (hasText(themeTag) && themeTag.trim().toUpperCase(Locale.ROOT).startsWith("AI_CHIP")) {
            return LEGACY_AI_CHIP_SEED_SOURCE;
        }
        return LEGACY_THEME_MAPPING_SOURCE;
    }

    private static boolean containsAny(String text, String... needles) {
        String upperText = text.toUpperCase(Locale.ROOT);
        for (String needle : needles) {
            if (upperText.contains(needle.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
