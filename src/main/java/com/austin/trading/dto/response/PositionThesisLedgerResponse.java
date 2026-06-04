package com.austin.trading.dto.response;

import com.austin.trading.entity.PositionThesisLedgerEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PositionThesisLedgerResponse(
        LocalDateTime evaluatedAt,
        boolean readOnly,
        boolean productionDecisionAllowed,
        boolean autoBuyEnabled,
        boolean autoSellEnabled,
        boolean manualConfirmRequired,
        int positionCount,
        List<Item> items
) {
    public static PositionThesisLedgerResponse of(List<Item> items) {
        return new PositionThesisLedgerResponse(
                LocalDateTime.now(), true, false, false, false, true,
                items == null ? 0 : items.size(), items == null ? List.of() : items);
    }

    public record Item(
            Long id,
            String symbol,
            String stockName,
            Long positionId,
            Long paperTradeId,
            LocalDate entryDate,
            BigDecimal avgCost,
            String entrySource,
            Long entryDecisionId,
            String primaryTheme,
            String secondaryThemes,
            String thesisSummary,
            String themeLifecycle,
            BigDecimal themeHeat,
            BigDecimal themeBreadth,
            BigDecimal rotationStrength,
            BigDecimal narrativeHeat,
            String crowdingRisk,
            String institutionalAlignment,
            String wavePhase,
            String marketContext,
            String sectorLeadership,
            Boolean themeStillActive,
            String entryReason,
            Integer expectedHoldingDays,
            String invalidationCondition,
            String stopType,
            String targetWave,
            String thesisStatus,
            BigDecimal thesisConfidence,
            LocalDateTime latestReviewDate,
            String latestReviewReason,
            String evidenceJson,
            String themeContextStatus,
            LocalDate themeDataDate,
            long themeStaleDays,
            boolean productionDecisionAllowed,
            boolean autoBuyEnabled,
            boolean autoSellEnabled,
            boolean manualConfirmRequired,
            String safetyNote
    ) {
        public static Item from(PositionThesisLedgerEntity e) {
            String status = themeContextStatus(e);
            LocalDate themeDate = extractDate(e.getMarketContext(), "latestValidTradingDate");
            long staleDays = extractLong(e.getMarketContext(), "staleDays");
            return new Item(
                    e.getId(), e.getSymbol(), e.getStockName(), e.getPositionId(), e.getPaperTradeId(),
                    e.getEntryDate(), e.getAvgCost(), e.getEntrySource(), e.getEntryDecisionId(),
                    e.getPrimaryTheme(), e.getSecondaryThemes(), e.getThesisSummary(),
                    e.getThemeLifecycle(), e.getThemeHeat(), e.getThemeBreadth(), e.getRotationStrength(),
                    e.getNarrativeHeat(), e.getCrowdingRisk(), e.getInstitutionalAlignment(), e.getWavePhase(),
                    e.getMarketContext(), e.getSectorLeadership(), e.getThemeStillActive(),
                    e.getEntryReason(), e.getExpectedHoldingDays(), e.getInvalidationCondition(), e.getStopType(), e.getTargetWave(),
                    e.getThesisStatus(), e.getThesisConfidence(), e.getLatestReviewDate(), e.getLatestReviewReason(),
                    e.getEvidenceJson(), status, themeDate, staleDays, false, false, false, true,
                    "Position Thesis Ledger is read-only/manual-confirm evidence only; it never changes BUY/ENTER, never auto-buys, never auto-sells, and never bypasses stops.");
        }

        private static String themeContextStatus(PositionThesisLedgerEntity e) {
            if (e.getPrimaryTheme() == null || e.getPrimaryTheme().isBlank()) return "MISSING_THEME_CONTEXT";
            String ctx = e.getMarketContext() == null ? "" : e.getMarketContext();
            if (ctx.contains("FUTURE_DATA_DETECTED")) return "FUTURE_DATA_DETECTED";
            if (ctx.contains("STALE") || extractLong(ctx, "staleDays") > 0) return "STALE_THEME_CONTEXT";
            if (ctx.contains("MISSING_THEME_CONTEXT") || "UNKNOWN".equalsIgnoreCase(e.getThemeLifecycle())) return "MISSING_THEME_CONTEXT";
            return "LIVE_THEME_CONTEXT";
        }

        private static LocalDate extractDate(String json, String key) {
            if (json == null) return null;
            Matcher m = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"(\\d{4}-\\d{2}-\\d{2})\\\"").matcher(json);
            if (!m.find()) return null;
            try { return LocalDate.parse(m.group(1)); } catch (Exception ignored) { return null; }
        }

        private static long extractLong(String json, String key) {
            if (json == null) return 0;
            Matcher m = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)").matcher(json);
            if (!m.find()) return 0;
            try { return Long.parseLong(m.group(1)); } catch (Exception ignored) { return 0; }
        }
    }
}
