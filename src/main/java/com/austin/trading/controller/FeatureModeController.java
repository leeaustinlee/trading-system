package com.austin.trading.controller;

import com.austin.trading.dto.response.FeatureModeResponse;
import com.austin.trading.dto.response.FeatureModeSummaryResponse;
import com.austin.trading.service.FeatureModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** W1-4 read-only feature mode observability API. */
@RestController
@RequestMapping("/api/feature-modes")
public class FeatureModeController {

    private final FeatureModeService service;

    public FeatureModeController(FeatureModeService service) {
        this.service = service;
    }

    @GetMapping
    public List<FeatureModeResponse> list() {
        return service.list();
    }

    @GetMapping("/summary")
    public FeatureModeSummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/{featureKey}")
    public ResponseEntity<FeatureModeResponse> byFeatureKey(@PathVariable String featureKey) {
        return service.find(featureKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
