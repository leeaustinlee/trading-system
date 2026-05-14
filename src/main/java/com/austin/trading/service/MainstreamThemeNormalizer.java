package com.austin.trading.service;

import java.util.Locale;

final class MainstreamThemeNormalizer {
    private MainstreamThemeNormalizer() {}

    static String normalize(String themeTag, String reason) {
        String text = ((themeTag == null ? "" : themeTag) + " " + (reason == null ? "" : reason))
                .trim()
                .toUpperCase(Locale.ROOT);
        if (text.isBlank()) return "UNKNOWN";
        if (containsAny(text, "UNKNOWN", "未分類", "無法", "不明")) return "UNKNOWN";
        if (containsAny(text, "其他強勢股", "OTHER")) return "OTHER";
        if (containsAny(text, "PCB", "CCL", "銅箔", "載板", "ABF", "印刷電路", "欣興", "景碩", "金居")) return "PCB";
        if (containsAny(text, "MEMORY", "DRAM", "NAND", "HBM", "記憶體", "南亞科", "華邦電", "群聯", "威剛")) return "MEMORY";
        if (containsAny(text, "AI SERVER", "AI_SERVER", "伺服器", "SERVER", "GB200", "GB300", "機櫃", "緯穎", "廣達", "技嘉")) return "AI_SERVER";
        if (containsAny(text, "ROBOT", "ROBOTICS", "機器人", "自動化", "上銀", "直得")) return "ROBOTICS";
        if (containsAny(text, "DEFENSE", "軍工", "國防", "航太", "雷虎", "漢翔")) return "DEFENSE";
        if (containsAny(text, "POWER", "電源", "供電", "UPS", "BBU", "電池", "台達電", "光寶")) return "POWER";
        if (containsAny(text, "COOLING", "散熱", "水冷", "液冷", "風扇", "雙鴻", "奇鋐", "建準")) return "COOLING";
        if (containsAny(text, "SEMICONDUCTOR", "SEMI", "半導體", "晶圓", "IC", "ASIC", "封測", "設備", "台積電", "聯發科", "世芯")) return "SEMICONDUCTOR";
        return "OTHER";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
