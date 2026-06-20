package com.austin.trading.controller;

import com.austin.trading.dto.response.RankingTopNShadowCalibrationResponse;
import com.austin.trading.service.RankingTopNShadowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET-only read-only/shadow-only Top-N ranking calibration APIs.
 *
 * <p>These endpoints report what broader Top-N windows would have shown; they do
 * not mutate ranking configuration, ranking output, candidates, watchlists,
 * final decisions, paper trades, positions, or buy/sell/risk gates.</p>
 */
@RestController
@RequestMapping("/api/ranking/topn-shadow")
public class RankingTopNShadowCalibrationController {
    private final RankingTopNShadowService service;

    public RankingTopNShadowCalibrationController(RankingTopNShadowService service) {
        this.service = service;
    }

    @GetMapping("/calibration")
    public ResponseEntity<RankingTopNShadowCalibrationResponse> calibration(
            @RequestParam(defaultValue = "60") int days) {
        return ResponseEntity.ok(service.calibration(days));
    }

    @GetMapping("/missed-winners")
    public ResponseEntity<RankingTopNShadowCalibrationResponse.MissedWinnersResponse> missedWinners(
            @RequestParam(defaultValue = "60") int days) {
        return ResponseEntity.ok(service.missedWinners(days));
    }

    @GetMapping("/theme-quota")
    public ResponseEntity<RankingTopNShadowCalibrationResponse.ThemeQuotaResponse> themeQuota(
            @RequestParam(defaultValue = "60") int days) {
        return ResponseEntity.ok(service.themeQuota(days));
    }
}
