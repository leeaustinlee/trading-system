package com.austin.trading.controller;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.request.PositionUpdateRequest;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.PositionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final PositionService positionService;
    private final CandidateScanService candidateScanService;

    public PositionController(PositionService positionService, CandidateScanService candidateScanService) {
        this.positionService = positionService;
        this.candidateScanService = candidateScanService;
    }

    @GetMapping("/open")
    public List<PositionResponse> getOpenPositions(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (symbol == null && page == 0) return positionService.getOpenPositions(size);
        return positionService.getOpenPositionsFiltered(symbol, page, size);
    }

    @GetMapping("/history")
    public List<PositionResponse> getHistory(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size
    ) {
        if (symbol == null && dateFrom == null && dateTo == null && page == 0)
            return positionService.getHistory(size);
        return positionService.getHistoryFiltered(symbol, dateFrom, dateTo, page, size);
    }

    /** 持倉即時報價（TSE→OTC fallback） */
    @GetMapping("/live-quotes")
    public List<LiveQuoteResponse> getLiveQuotes(@RequestParam(required = false) String symbols) {
        if (symbols == null || symbols.isBlank()) return List.of();
        List<String> syms = Arrays.stream(symbols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return candidateScanService.getLiveQuotesBySymbols(syms);
    }

    /** 新增持倉 */
    @PostMapping
    public PositionResponse create(@Valid @RequestBody PositionCreateRequest request) {
        return positionService.create(request);
    }

    /** 編輯持倉（停損停利 / 加減碼 / 備註） */
    @PatchMapping("/{id}")
    public PositionResponse update(
            @PathVariable Long id,
            @RequestBody PositionUpdateRequest request
    ) {
        return positionService.update(id, request);
    }

    /** 出清持倉 */
    @PatchMapping("/{id}/close")
    public PositionResponse close(
            @PathVariable Long id,
            @Valid @RequestBody PositionCloseRequest request
    ) {
        return positionService.close(id, request);
    }
}
