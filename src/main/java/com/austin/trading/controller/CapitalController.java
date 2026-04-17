package com.austin.trading.controller;

import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.service.CapitalService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/capital")
public class CapitalController {

    private final CapitalService capitalService;

    public CapitalController(CapitalService capitalService) {
        this.capitalService = capitalService;
    }

    /** 取得資金設定（不含即時報價，快速） */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        CapitalConfigEntity cfg = capitalService.getConfig();
        return Map.of(
                "availableCash", cfg.getAvailableCash() != null ? cfg.getAvailableCash() : BigDecimal.ZERO,
                "notes",         cfg.getNotes()      != null ? cfg.getNotes()      : "",
                "updatedAt",     cfg.getUpdatedAt()  != null ? cfg.getUpdatedAt().toString() : ""
        );
    }

    /**
     * 更新資金設定。
     * Body: { "availableCash": 300000, "notes": "2026-04 帳戶重置" }
     */
    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        BigDecimal cash = body.containsKey("availableCash")
                ? new BigDecimal(body.get("availableCash").toString()) : null;
        String notes = body.containsKey("notes") ? body.get("notes").toString() : null;
        CapitalConfigEntity cfg = capitalService.updateConfig(cash, notes);
        return Map.of(
                "availableCash", cfg.getAvailableCash(),
                "notes",         cfg.getNotes()     != null ? cfg.getNotes()     : "",
                "updatedAt",     cfg.getUpdatedAt() != null ? cfg.getUpdatedAt().toString() : ""
        );
    }

    /**
     * 完整資金總覽：可動用現金 + 持倉現值（呼叫 TWSE 即時報價）。
     * 供 Dashboard 顯示與 Codex 決策前查詢。
     */
    @GetMapping("/summary")
    public CapitalSummaryResponse getSummary() {
        return capitalService.getSummary();
    }
}
