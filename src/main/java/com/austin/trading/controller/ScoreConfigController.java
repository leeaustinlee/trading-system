package com.austin.trading.controller;

import com.austin.trading.dto.response.ScoreConfigResponse;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 評分與策略參數設定 API。
 *
 * <ul>
 *   <li>GET  /api/config/score          取得全部設定</li>
 *   <li>PUT  /api/config/score/{key}    更新單一設定值</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/config/score")
public class ScoreConfigController {

    private final ScoreConfigService scoreConfigService;

    public ScoreConfigController(ScoreConfigService scoreConfigService) {
        this.scoreConfigService = scoreConfigService;
    }

    @GetMapping
    public List<ScoreConfigResponse> getAll() {
        return scoreConfigService.getAll();
    }

    @GetMapping("/{key}")
    public ResponseEntity<ScoreConfigResponse> getByKey(@PathVariable String key) {
        try {
            return ResponseEntity.ok(scoreConfigService.getByKey(key));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(
            @PathVariable String key,
            @RequestBody Map<String, String> body
    ) {
        String value = body.get("value");
        if (value == null || value.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "value 不可為空"));
        }
        try {
            return ResponseEntity.ok(scoreConfigService.update(key, value));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
