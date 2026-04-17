package com.austin.trading.controller;

import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.service.MarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/current")
    public ResponseEntity<MarketCurrentResponse> getCurrent() {
        return marketDataService.getCurrentMarket()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public List<MarketCurrentResponse> getHistory(@RequestParam(defaultValue = "50") int limit) {
        return marketDataService.getMarketHistory(limit);
    }
}
