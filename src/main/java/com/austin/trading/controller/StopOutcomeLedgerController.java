package com.austin.trading.controller;

import com.austin.trading.service.StopOutcomeLedgerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/stop-outcome-ledger")
public class StopOutcomeLedgerController {
    private final StopOutcomeLedgerService service;

    public StopOutcomeLedgerController(StopOutcomeLedgerService service) {
        this.service = service;
    }

    @PostMapping("/refresh")
    public ResponseEntity<StopOutcomeLedgerService.RefreshSummary> refresh(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromExitDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate) {
        return ResponseEntity.ok(service.refresh(fromExitDate, referenceDate));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "90") int days) {
        return ResponseEntity.ok(service.summary(days));
    }
}
