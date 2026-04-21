package com.austin.trading.service;

import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.repository.CapitalConfigRepository;
import com.austin.trading.repository.CapitalLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 啟動時一次性的 ledger 遷移（方案 1 保守上線）。
 *
 * <p>條件：<b>ledger 完全為空</b> 且 {@code capital_config.available_cash > 0}
 * → 寫一筆 {@code INITIAL_BALANCE}，金額 = {@code capital_config.available_cash}。</p>
 *
 * <p>決策理由（保守遷移）：</p>
 * <ul>
 *   <li>歷史 open/closed position 的成交流水無券商來源資料，<b>不回建</b>避免偽造帳。</li>
 *   <li>現有 {@code available_cash} 視為「此刻 onboarding 的起始餘額」。</li>
 *   <li>bootstrap 完成後不會再自動動 ledger；後續所有現金變動都透過 {@link CapitalLedgerService}。</li>
 *   <li>本 bootstrap 是 idempotent — ledger 非空時就跳過，重啟安全。</li>
 * </ul>
 */
@Service
public class CapitalBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(CapitalBootstrapService.class);

    private final CapitalConfigRepository configRepository;
    private final CapitalLedgerRepository ledgerRepository;
    private final CapitalLedgerService    ledgerService;

    public CapitalBootstrapService(CapitalConfigRepository configRepository,
                                     CapitalLedgerRepository ledgerRepository,
                                     CapitalLedgerService ledgerService) {
        this.configRepository = configRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService    = ledgerService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapIfNeeded() {
        long count = ledgerRepository.count();
        if (count > 0) {
            log.info("[CapitalBootstrap] ledger 已有 {} 筆資料，略過 bootstrap。", count);
            return;
        }

        CapitalConfigEntity cfg = configRepository.findById(1L).orElse(null);
        if (cfg == null || cfg.getAvailableCash() == null
                || cfg.getAvailableCash().signum() <= 0) {
            log.info("[CapitalBootstrap] capital_config.available_cash 未設定或為 0，不建立 INITIAL_BALANCE。"
                    + " 請使用 POST /api/capital/deposit 手動入金啟動。");
            return;
        }

        BigDecimal seed = cfg.getAvailableCash();
        ledgerService.recordInitialBalance(seed,
                "bootstrap from capital_config.available_cash on migration (legacy snapshot)");
        log.warn("[CapitalBootstrap] ✅ 已從 capital_config.available_cash 建立 INITIAL_BALANCE = {}。"
                + " 之後所有現金變動請透過 ledger（/api/capital/deposit /withdraw /adjust 或 position create/close）。",
                seed);
    }
}
