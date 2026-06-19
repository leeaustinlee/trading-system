package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeLifecycleCalibrationResponse;
import com.austin.trading.service.ThemeLifecycleCalibrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/theme-lifecycle")
public class ThemeLifecycleCalibrationController {
    private final ThemeLifecycleCalibrationService service;

    public ThemeLifecycleCalibrationController(ThemeLifecycleCalibrationService service) {
        this.service = service;
    }

    @GetMapping("/calibration")
    public ResponseEntity<ThemeLifecycleCalibrationResponse> calibration(
            @RequestParam(defaultValue = "60") int days) {
        return ResponseEntity.ok(service.calibration(days));
    }

    @GetMapping("/data-gaps")
    public ResponseEntity<Map<String, Object>> dataGaps(
            @RequestParam(defaultValue = "60") int days) {
        return ResponseEntity.ok(service.dataGaps(days));
    }
}
