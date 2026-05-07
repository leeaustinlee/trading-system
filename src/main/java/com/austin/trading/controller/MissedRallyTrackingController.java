package com.austin.trading.controller;

import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.service.MissedRallyTrackingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/missed-rallies")
public class MissedRallyTrackingController {
    private final MissedRallyTrackingService service;
    public MissedRallyTrackingController(MissedRallyTrackingService service) { this.service = service; }
    @GetMapping("/recent") public List<MissedRallyTrackingEntity> recent(@RequestParam(defaultValue = "20") int limit) { return service.recent(limit); }
    @GetMapping("/summary") public Map<String, Object> summary() { return service.summary(); }
    @GetMapping("/by-gate") public List<Map<String, Object>> byGate() { return service.byGate(); }
    @GetMapping("/by-strategy") public List<Map<String, Object>> byStrategy() { return service.byStrategy(); }
}
