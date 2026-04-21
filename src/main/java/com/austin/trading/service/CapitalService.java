package com.austin.trading.service;

import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.dto.response.DailyPnlResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CapitalConfigRepository;
import com.austin.trading.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 資金總覽 / 設定服務（v3：ledger-backed）。
 *
 * <p><b>主帳來源</b>：{@link CapitalLedgerService#getCashBalance()} —
 * 由 {@code capital_ledger} 流水累加推導，不再依賴 {@code capital_config.available_cash}。</p>
 *
 * <p><b>capital_config</b> 降級為設定：保留 reservedCash + notes，
 * available_cash 欄位只作為首次啟動的遷移來源（見 {@link CapitalBootstrapService}）。</p>
 */
@Service
public class CapitalService {

    private static final Long CONFIG_ID = 1L;

    private final CapitalConfigRepository capitalConfigRepository;
    private final PositionRepository      positionRepository;
    private final CandidateScanService    candidateScanService;
    private final CapitalLedgerService    ledgerService;
    private final PnlService              pnlService;

    public CapitalService(
            CapitalConfigRepository capitalConfigRepository,
            PositionRepository      positionRepository,
            CandidateScanService    candidateScanService,
            CapitalLedgerService    ledgerService,
            PnlService              pnlService
    ) {
        this.capitalConfigRepository = capitalConfigRepository;
        this.positionRepository      = positionRepository;
        this.candidateScanService    = candidateScanService;
        this.ledgerService           = ledgerService;
        this.pnlService              = pnlService;
    }

    // ── 設定（保留 reservedCash / notes）─────────────────────────────────

    /** 取得資金設定（不含即時報價，快速）*/
    public CapitalConfigEntity getConfig() {
        return capitalConfigRepository.findById(CONFIG_ID)
                .orElseGet(this::defaultConfig);
    }

    /** 取得現金餘額（ledger 累計）*/
    public BigDecimal getCashBalance() {
        return ledgerService.getCashBalance();
    }

    /** 可動用現金 = cashBalance − reservedCash，下限 0 */
    public BigDecimal getAvailableCash() {
        BigDecimal balance  = getCashBalance();
        BigDecimal reserved = getConfig().getReservedCash();
        if (reserved == null) reserved = BigDecimal.ZERO;
        BigDecimal avail = balance.subtract(reserved);
        return avail.signum() < 0 ? BigDecimal.ZERO : avail;
    }

    /**
     * 更新設定（v3 語義）：
     * <ul>
     *   <li>body 的 {@code availableCash}：舊 UI 仍會送此欄位表達「把餘額設定為 X」，
     *       改為與當前 cashBalance 比較，差額自動寫一筆 {@code MANUAL_ADJUST}。
     *       想指定為 0 可以傳 0；這是唯一會動到 ledger 的設定路徑。</li>
     *   <li>body 的 {@code reservedCash}：寫入 capital_config.reserved_cash。</li>
     *   <li>body 的 {@code notes}：寫入 capital_config.notes。</li>
     * </ul>
     * 舊欄位 {@code capital_config.available_cash} 不再被主流程寫入，
     * 僅在 bootstrap 時讀一次（見 {@link CapitalBootstrapService}）。
     */
    @Transactional
    public CapitalConfigEntity updateConfig(BigDecimal targetCashBalance,
                                             BigDecimal reservedCash,
                                             String notes) {
        CapitalConfigEntity cfg = capitalConfigRepository.findById(CONFIG_ID)
                .orElseGet(this::defaultConfig);
        if (reservedCash != null) cfg.setReservedCash(reservedCash);
        if (notes        != null) cfg.setNotes(notes);
        cfg.setUpdatedAt(LocalDateTime.now());
        CapitalConfigEntity saved = capitalConfigRepository.save(cfg);

        if (targetCashBalance != null) {
            BigDecimal current = getCashBalance();
            BigDecimal delta   = targetCashBalance.subtract(current);
            if (delta.signum() != 0) {
                ledgerService.recordManualAdjust(delta,
                        "config update: set cashBalance to " + targetCashBalance,
                        "CONFIG_UPDATE");
            }
        }
        return saved;
    }

    // ── Summary（由 ledger + open positions 推導）────────────────────────

    public CapitalSummaryResponse getSummary() {
        CapitalConfigEntity cfg       = getConfig();
        BigDecimal          cashBal   = getCashBalance();
        BigDecimal          reserved  = cfg.getReservedCash() == null ? BigDecimal.ZERO : cfg.getReservedCash();
        BigDecimal          available = cashBal.subtract(reserved);
        if (available.signum() < 0) available = BigDecimal.ZERO;

        // open positions
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");

        BigDecimal investedCost = openPositions.stream()
                .map(p -> p.getAvgCost() != null && p.getQty() != null
                        ? p.getAvgCost().multiply(p.getQty()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);

        // 抓即時報價
        List<String> symbols = openPositions.stream()
                .map(PositionEntity::getSymbol)
                .distinct().collect(Collectors.toList());

        boolean    liveAvailable  = false;
        BigDecimal investedValue  = null;
        BigDecimal unrealizedPnl  = null;

        if (!symbols.isEmpty()) {
            try {
                List<LiveQuoteResponse> quotes = candidateScanService.getLiveQuotesBySymbols(symbols);
                Map<String, Double> priceMap = quotes.stream()
                        .filter(q -> q.currentPrice() != null)
                        .collect(Collectors.toMap(
                                LiveQuoteResponse::symbol,
                                LiveQuoteResponse::currentPrice,
                                (a, b) -> a));

                if (!priceMap.isEmpty()) {
                    liveAvailable = true;
                    BigDecimal totalValue      = BigDecimal.ZERO;
                    BigDecimal totalUnrealized = BigDecimal.ZERO;

                    for (PositionEntity p : openPositions) {
                        if (p.getAvgCost() == null || p.getQty() == null) continue;
                        Double cur = priceMap.get(p.getSymbol());
                        if (cur == null) continue;

                        BigDecimal curBD = BigDecimal.valueOf(cur);
                        BigDecimal value = curBD.multiply(p.getQty());
                        totalValue = totalValue.add(value);

                        boolean isShort = "SHORT".equalsIgnoreCase(p.getSide())
                                || "做空".equals(p.getSide());
                        BigDecimal diff = isShort
                                ? p.getAvgCost().subtract(curBD)
                                : curBD.subtract(p.getAvgCost());
                        totalUnrealized = totalUnrealized.add(diff.multiply(p.getQty()));
                    }
                    investedValue = totalValue.setScale(0, RoundingMode.HALF_UP);
                    unrealizedPnl = totalUnrealized.setScale(0, RoundingMode.HALF_UP);
                }
            } catch (Exception ignored) { /* 報價失敗不影響主流程 */ }
        }

        // realizedPnl：近 30 天 daily_pnl 合計（現有 PnlService 聚合）
        BigDecimal realizedPnl = BigDecimal.ZERO;
        try {
            List<DailyPnlResponse> rows = pnlService.getDailyHistory(30);
            realizedPnl = rows.stream()
                    .map(r -> r.grossPnl() != null ? r.grossPnl()
                            : r.realizedPnl() != null ? r.realizedPnl() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(0, RoundingMode.HALF_UP);
        } catch (Exception ignored) { /* 若 pnl 表有問題不擋主流程 */ }

        BigDecimal effectiveValue = investedValue != null ? investedValue : investedCost;
        BigDecimal totalEquity    = cashBal.add(effectiveValue).setScale(0, RoundingMode.HALF_UP);

        BigDecimal cashRatio = totalEquity.compareTo(BigDecimal.ZERO) > 0
                ? available.divide(totalEquity, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(100);

        String updatedAt = cfg.getUpdatedAt() != null
                ? cfg.getUpdatedAt().format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                : null;

        return new CapitalSummaryResponse(
                available.setScale(0, RoundingMode.HALF_UP),
                cashBal.setScale(0, RoundingMode.HALF_UP),
                reserved.setScale(0, RoundingMode.HALF_UP),
                investedCost,
                investedValue,
                unrealizedPnl,
                realizedPnl,
                totalEquity,
                totalEquity,                       // totalAssets 保留欄位 = totalEquity
                cashRatio,
                openPositions.size(),
                liveAvailable,
                cfg.getNotes(),
                updatedAt
        );
    }

    // ── helpers ────────────────────────────────────────────────────────

    private CapitalConfigEntity defaultConfig() {
        CapitalConfigEntity e = new CapitalConfigEntity();
        e.setId(CONFIG_ID);
        e.setAvailableCash(BigDecimal.ZERO);
        e.setReservedCash(BigDecimal.ZERO);
        return e;
    }
}
