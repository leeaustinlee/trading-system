package com.austin.trading.controller;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.service.CandidateScanService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidateScanService candidateScanService;

    public CandidateController(CandidateScanService candidateScanService) {
        this.candidateScanService = candidateScanService;
    }

    @GetMapping("/current")
    public List<CandidateResponse> getCurrent(@RequestParam(defaultValue = "20") int limit) {
        return candidateScanService.getCurrentCandidates(limit);
    }

    @GetMapping("/history")
    public List<CandidateResponse> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "100") int limit
    ) {
        if (date != null) {
            return candidateScanService.getCandidatesByDate(date, limit);
        }
        return candidateScanService.getCandidatesHistory(limit);
    }
}
