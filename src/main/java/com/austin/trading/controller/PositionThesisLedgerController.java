package com.austin.trading.controller;

import com.austin.trading.dto.response.PositionThesisLedgerResponse;
import com.austin.trading.service.PositionThesisLedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/position-thesis-ledger")
public class PositionThesisLedgerController {
    private final PositionThesisLedgerService service;

    public PositionThesisLedgerController(PositionThesisLedgerService service) {
        this.service = service;
    }

    @PostMapping("/refresh")
    public PositionThesisLedgerResponse refresh() {
        return service.refreshOpenTheses();
    }

    @GetMapping("/open")
    public PositionThesisLedgerResponse open() {
        return service.openTheses();
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<PositionThesisLedgerResponse.Item> bySymbol(@PathVariable String symbol) {
        return service.getOpenThesisItemBySymbol(symbol)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
