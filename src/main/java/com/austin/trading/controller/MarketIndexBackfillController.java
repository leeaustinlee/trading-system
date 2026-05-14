package com.austin.trading.controller;

import com.austin.trading.service.regime.MarketIndexSymbolBackfillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/market-index")
public class MarketIndexBackfillController {

    private final MarketIndexSymbolBackfillService symbolBackfillService;

    public MarketIndexBackfillController(MarketIndexSymbolBackfillService symbolBackfillService) {
        this.symbolBackfillService = symbolBackfillService;
    }

    @PostMapping("/backfill-symbols")
    public Map<String, Object> backfillSymbols(@RequestParam(defaultValue = "90") int days,
                                               @RequestParam(required = false) String symbols) {
        return symbolBackfillService.backfillSymbols(days, symbols);
    }
}
