package com.austin.trading.controller;

import com.austin.trading.dto.response.CapitalLedgerResponse;
import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.entity.LedgerType;
import com.austin.trading.repository.CapitalLedgerRepository;
import com.austin.trading.service.CapitalLedgerService;
import com.austin.trading.service.CapitalService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 資金帳務 REST。
 *
 * <p>v3 起：</p>
 * <ul>
 *   <li>{@code GET  /api/capital/config}        — 讀設定（含 cashBalance / reservedCash / notes）</li>
 *   <li>{@code PUT  /api/capital/config}        — 寫 reservedCash / notes；若 body 帶 availableCash
 *       會與當前 cashBalance 差額寫 MANUAL_ADJUST（相容舊 UI）</li>
 *   <li>{@code GET  /api/capital/summary}       — 總覽（推導值）</li>
 *   <li>{@code GET  /api/capital/ledger}        — 流水歷史（支援 type/symbol/date filter）</li>
 *   <li>{@code POST /api/capital/deposit}       — 入金</li>
 *   <li>{@code POST /api/capital/withdraw}      — 出金</li>
 *   <li>{@code POST /api/capital/adjust}        — 手動調帳（正負）</li>
 *   <li>{@code PUT  /api/capital/reserved}      — 更新 reserved cash</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/capital")
public class CapitalController {

    private final CapitalService capitalService;
    private final CapitalLedgerService ledgerService;
    private final CapitalLedgerRepository ledgerRepository;

    public CapitalController(CapitalService capitalService,
                              CapitalLedgerService ledgerService,
                              CapitalLedgerRepository ledgerRepository) {
        this.capitalService = capitalService;
        this.ledgerService  = ledgerService;
        this.ledgerRepository = ledgerRepository;
    }

    /** 取得資金設定 + 即時 cashBalance（不撈即時報價，快速） */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        CapitalConfigEntity cfg   = capitalService.getConfig();
        BigDecimal          cash  = capitalService.getCashBalance();
        BigDecimal          resv  = cfg.getReservedCash() == null ? BigDecimal.ZERO : cfg.getReservedCash();
        BigDecimal          avail = capitalService.getAvailableCash();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("availableCash", avail);          // 向下相容：= cashBalance − reservedCash
        body.put("cashBalance",   cash);
        body.put("reservedCash",  resv);
        body.put("notes",         cfg.getNotes()      != null ? cfg.getNotes()      : "");
        body.put("updatedAt",     cfg.getUpdatedAt()  != null ? cfg.getUpdatedAt().toString() : "");
        return body;
    }

    /**
     * 更新資金設定。
     * <p>Body（皆 optional）：</p>
     * <pre>{@code
     * {
     *   "availableCash": 300000,   // 目標 cashBalance；差額會寫 MANUAL_ADJUST（相容舊 UI）
     *   "reservedCash":  10000,    // 保留備用金
     *   "notes":         "..."
     * }
     * }</pre>
     */
    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        BigDecimal targetCash = body.containsKey("availableCash") && body.get("availableCash") != null
                ? new BigDecimal(body.get("availableCash").toString()) : null;
        BigDecimal reserved = body.containsKey("reservedCash") && body.get("reservedCash") != null
                ? new BigDecimal(body.get("reservedCash").toString()) : null;
        String notes = body.containsKey("notes") && body.get("notes") != null
                ? body.get("notes").toString() : null;

        capitalService.updateConfig(targetCash, reserved, notes);
        return getConfig();
    }

    /** 完整資金總覽（含即時報價、持倉現值、未實現 / 已實現損益）。 */
    @GetMapping("/summary")
    public CapitalSummaryResponse getSummary() {
        return capitalService.getSummary();
    }

    // ── v3：ledger 相關 ────────────────────────────────────────────────

    /** 查詢 ledger 歷史 */
    @GetMapping("/ledger")
    public List<CapitalLedgerResponse> getLedger(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "200") int size
    ) {
        LedgerType ledgerType = parseLedgerType(type);
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.trim();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 500));
        return ledgerRepository.findByFilter(ledgerType, sym, from, to, PageRequest.of(safePage, safeSize))
                .stream().map(CapitalLedgerResponse::from).toList();
    }

    /** 手動入金 */
    @PostMapping("/deposit")
    public Map<String, Object> deposit(@RequestBody Map<String, Object> body) {
        BigDecimal amount = requireAmount(body);
        String note = optString(body, "note");
        var e = ledgerService.recordDeposit(amount, note, "UI");
        return ledgerActionResponse(e);
    }

    /** 手動出金（不足 409） */
    @PostMapping("/withdraw")
    public Map<String, Object> withdraw(@RequestBody Map<String, Object> body) {
        BigDecimal amount = requireAmount(body);
        String note = optString(body, "note");
        var e = ledgerService.recordWithdraw(amount, note, "UI");
        return ledgerActionResponse(e);
    }

    /**
     * 手動調帳（正負皆可）。
     * Body：{@code { "amount": 500, "note": "帳差修正" }}
     */
    @PostMapping("/adjust")
    public Map<String, Object> adjust(@RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("amount") || body.get("amount") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount required (can be positive or negative)");
        }
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String note = optString(body, "note");
        var e = ledgerService.recordManualAdjust(amount, note, "UI");
        return ledgerActionResponse(e);
    }

    /** 更新 reserved cash（獨立端點，不觸碰 ledger） */
    @PutMapping("/reserved")
    public Map<String, Object> setReserved(@RequestBody Map<String, Object> body) {
        BigDecimal reserved = body.containsKey("reservedCash") && body.get("reservedCash") != null
                ? new BigDecimal(body.get("reservedCash").toString())
                : BigDecimal.ZERO;
        String notes = optString(body, "notes");
        capitalService.updateConfig(null, reserved, notes);
        return getConfig();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static BigDecimal requireAmount(Map<String, Object> body) {
        if (body == null || !body.containsKey("amount") || body.get("amount") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount required");
        }
        BigDecimal amt = new BigDecimal(body.get("amount").toString());
        if (amt.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        return amt;
    }

    private static String optString(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        String s = body.get(key).toString();
        return s.isBlank() ? null : s;
    }

    private static LedgerType parseLedgerType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LedgerType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ledger type: " + s);
        }
    }

    private Map<String, Object> ledgerActionResponse(com.austin.trading.entity.CapitalLedgerEntity e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entry",        e == null ? null : CapitalLedgerResponse.from(e));
        body.put("cashBalance",  capitalService.getCashBalance());
        body.put("reservedCash", capitalService.getConfig().getReservedCash());
        body.put("availableCash", capitalService.getAvailableCash());
        return body;
    }
}
