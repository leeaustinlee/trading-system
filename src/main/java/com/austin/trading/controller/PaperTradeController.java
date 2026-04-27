package com.austin.trading.controller;

import com.austin.trading.dto.response.PaperTradeResponse;
import com.austin.trading.engine.PaperTradeKpiEngine;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.service.PaperTradeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 1 Paper Trade REST API。
 *
 * <ul>
 *   <li>GET  /api/paper-trades/open       — 目前持有的虛擬倉</li>
 *   <li>GET  /api/paper-trades/closed     — 已平倉清單(預設近 90 天)</li>
 *   <li>GET  /api/paper-trades/kpi        — KPI snapshot(win_rate / sharpe / drawdown 等)</li>
 *   <li>POST /api/paper-trades/recalculate?date=YYYY-MM-DD — 手動觸發 MTM(補單日)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/paper-trades")
public class PaperTradeController {

    private final PaperTradeService paperTradeService;
    private final PaperTradeKpiEngine kpiEngine;

    public PaperTradeController(PaperTradeService paperTradeService, PaperTradeKpiEngine kpiEngine) {
        this.paperTradeService = paperTradeService;
        this.kpiEngine = kpiEngine;
    }

    @GetMapping("/open")
    public List<PaperTradeResponse> open() {
        return paperTradeService.listOpen().stream().map(PaperTradeResponse::from).toList();
    }

    @GetMapping("/closed")
    public List<PaperTradeResponse> closed(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to",   required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return paperTradeService.listClosed(from, to).stream().map(PaperTradeResponse::from).toList();
    }

    @GetMapping("/kpi")
    public PaperTradeKpiEngine.KpiSnapshot kpi(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to",   required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<PaperTradeEntity> closed = paperTradeService.listClosed(from, to);
        return kpiEngine.compute(closed);
    }

    @PostMapping("/recalculate")
    public PaperTradeService.MtmSummary recalculate(
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return paperTradeService.markToMarketAll(date == null ? LocalDate.now() : date);
    }
}
