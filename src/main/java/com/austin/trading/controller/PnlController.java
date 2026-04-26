package com.austin.trading.controller;

import com.austin.trading.dto.request.DailyPnlCreateRequest;
import com.austin.trading.dto.request.DailyPnlUpdateRequest;
import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.dto.response.DailyPnlResponse;
import com.austin.trading.dto.response.DrawdownResponse;
import com.austin.trading.dto.response.PnlSummaryResponse;
import com.austin.trading.service.CapitalService;
import com.austin.trading.service.PnlService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/pnl")
public class PnlController {

    private final PnlService pnlService;
    private final CapitalService capitalService;

    public PnlController(PnlService pnlService, CapitalService capitalService) {
        this.pnlService = pnlService;
        this.capitalService = capitalService;
    }

    @GetMapping("/daily")
    public ResponseEntity<DailyPnlResponse> getDaily() {
        return pnlService.getLatestDaily()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/summary")
    public PnlSummaryResponse getSummary(@RequestParam(defaultValue = "20") int days) {
        return pnlService.getSummary(days);
    }

    /**
     * v2.14：rolling 最大回撤。預設 90 天視窗；baseline 取 capital.totalAssets。
     * 回傳 maxDrawdownPct / peakAt / troughAt / currentDrawdownPct（皆為負值或 0）。
     */
    @GetMapping("/drawdown")
    public DrawdownResponse getDrawdown(@RequestParam(defaultValue = "90") int days) {
        BigDecimal baseline = null;
        try {
            CapitalSummaryResponse cap = capitalService.getSummary();
            if (cap != null) {
                if (cap.totalAssets() != null) baseline = cap.totalAssets();
                else if (cap.totalEquity() != null) baseline = cap.totalEquity();
            }
        } catch (RuntimeException ignored) {
            // baseline 無資料時 service 內部會 fallback 到 max(peak,1)
        }
        return pnlService.computeDrawdown(days, baseline);
    }

    /**
     * GET /api/pnl/history
     * ?dateFrom=2026-04-01 &dateTo=2026-04-17 &page=0 &size=200 &limit=200
     * dateFrom/dateTo 有值時使用日期區間查詢；否則使用 limit 倒序查詢。
     */
    @GetMapping("/history")
    public List<DailyPnlResponse> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int limit
    ) {
        if (dateFrom != null || dateTo != null) {
            return pnlService.getDailyHistoryByRange(dateFrom, dateTo, page, limit);
        }
        return pnlService.getDailyHistory(limit);
    }

    @PostMapping("/daily")
    public DailyPnlResponse createDaily(@Valid @RequestBody DailyPnlCreateRequest request) {
        return pnlService.create(request);
    }

    /** 手動覆蓋損益（券商對帳後補入實際費稅/淨損益） */
    @PatchMapping("/daily/{id}")
    public DailyPnlResponse updateDaily(
            @PathVariable Long id,
            @RequestBody DailyPnlUpdateRequest request
    ) {
        return pnlService.updateDaily(id, request);
    }
}
