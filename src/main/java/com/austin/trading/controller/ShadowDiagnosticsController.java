package com.austin.trading.controller;

import com.austin.trading.dto.internal.RankingTopNShadowResultDto;
import com.austin.trading.dto.internal.ThemeAdmissionShadowDecisionDto;
import com.austin.trading.dto.internal.TradingFunnelTraceDto;
import com.austin.trading.service.ShadowDiagnosticsReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** GET-only read-only shadow diagnostics APIs. */
@RestController
public class ShadowDiagnosticsController {

    private final ShadowDiagnosticsReportService reportService;

    public ShadowDiagnosticsController(ShadowDiagnosticsReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/api/trading-funnel/conversion")
    public Map<String, Object> conversion(@RequestParam(defaultValue = "60") int days) {
        return reportService.conversion(days);
    }

    @GetMapping("/api/trading-funnel/symbol/{symbol}")
    public List<TradingFunnelTraceDto> symbol(@PathVariable String symbol,
                                              @RequestParam(defaultValue = "60") int days) {
        return reportService.symbolTrace(symbol, days);
    }

    @GetMapping("/api/theme-admission/shadow")
    public List<ThemeAdmissionShadowDecisionDto> themeAdmissionShadow(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.themeAdmissionShadow(date);
    }

    @GetMapping("/api/ranking/topn-shadow")
    public List<RankingTopNShadowResultDto> rankingTopNShadow(@RequestParam(defaultValue = "60") int days) {
        return reportService.rankingTopNShadow(days);
    }
}
