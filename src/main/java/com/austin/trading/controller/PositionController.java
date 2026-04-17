package com.austin.trading.controller;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.service.PositionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    /** GET /api/positions/open?symbol=&page=&size= */
    @GetMapping("/open")
    public List<PositionResponse> getOpenPositions(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (symbol == null && page == 0) {
            return positionService.getOpenPositions(size);
        }
        return positionService.getOpenPositionsFiltered(symbol, page, size);
    }

    /** GET /api/positions/history?symbol=&dateFrom=&dateTo=&page=&size= */
    @GetMapping("/history")
    public List<PositionResponse> getHistory(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size
    ) {
        if (symbol == null && dateFrom == null && dateTo == null && page == 0) {
            return positionService.getHistory(size);
        }
        return positionService.getHistoryFiltered(symbol, dateFrom, dateTo, page, size);
    }

    @PostMapping
    public PositionResponse create(@Valid @RequestBody PositionCreateRequest request) {
        return positionService.create(request);
    }

    /** PATCH /api/positions/{id}/close */
    @PatchMapping("/{id}/close")
    public PositionResponse close(
            @PathVariable Long id,
            @Valid @RequestBody PositionCloseRequest request
    ) {
        return positionService.close(id, request);
    }
}
