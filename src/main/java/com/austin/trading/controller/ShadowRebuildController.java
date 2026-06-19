package com.austin.trading.controller;

import com.austin.trading.service.RankingTopNShadowService;
import com.austin.trading.service.ThemeAdmissionShadowService;
import com.austin.trading.service.TradingFunnelTraceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Manual shadow-only rebuild endpoint. Writes only shadow diagnostics tables. */
@RestController
@RequestMapping("/api/shadow-rebuild")
public class ShadowRebuildController {

    private final TradingFunnelTraceService funnelTraceService;
    private final ThemeAdmissionShadowService themeAdmissionShadowService;
    private final RankingTopNShadowService rankingTopNShadowService;

    public ShadowRebuildController(TradingFunnelTraceService funnelTraceService,
                                   ThemeAdmissionShadowService themeAdmissionShadowService,
                                   RankingTopNShadowService rankingTopNShadowService) {
        this.funnelTraceService = funnelTraceService;
        this.themeAdmissionShadowService = themeAdmissionShadowService;
        this.rankingTopNShadowService = rankingTopNShadowService;
    }

    @PostMapping
    public Map<String, Object> rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "60") int days) {
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = startDate == null ? end.minusDays(Math.max(days, 1) - 1L) : startDate;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }

        int funnelRows = 0;
        int admissionRows = 0;
        int rankingRows = 0;
        int processedDays = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            funnelRows += funnelTraceService.rebuildForDate(date);
            admissionRows += themeAdmissionShadowService.rebuildForDate(date);
            rankingRows += rankingTopNShadowService.rebuildForDate(date);
            processedDays++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start);
        result.put("endDate", end);
        result.put("processedDays", processedDays);
        result.put("tradingFunnelTraceRows", funnelRows);
        result.put("themeAdmissionShadowRows", admissionRows);
        result.put("rankingTopNShadowRows", rankingRows);
        result.put("shadowOnly", true);
        return result;
    }
}
