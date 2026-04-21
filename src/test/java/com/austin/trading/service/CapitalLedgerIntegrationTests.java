package com.austin.trading.service;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.request.PositionPartialCloseRequest;
import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.entity.CapitalLedgerEntity;
import com.austin.trading.entity.LedgerType;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CapitalConfigRepository;
import com.austin.trading.repository.CapitalLedgerRepository;
import com.austin.trading.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v3 驗收：資金帳務與持倉聯動（ledger-backed）。
 *
 * <p>每個測試使用獨立 symbol 避免 OPEN 衝突；開頭清乾淨 ledger + 相關 open positions，
 * 並寫一筆 {@link LedgerType#INITIAL_BALANCE} 作為測試基準。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CapitalLedgerIntegrationTests {

    @Autowired CapitalLedgerService    ledgerService;
    @Autowired CapitalService          capitalService;
    @Autowired PositionService         positionService;
    @Autowired CapitalLedgerRepository ledgerRepository;
    @Autowired CapitalConfigRepository configRepository;
    @Autowired PositionRepository      positionRepository;

    /** 測試間避免 symbol 衝突：把時間戳末 6 碼塞進 symbol 後綴 */
    private String uniqueSymbol(String base) {
        return base + (System.nanoTime() % 1_000_000);
    }

    @BeforeEach
    void resetLedgerAndPositions() {
        // 清乾淨既存 open 部位避免 CONFLICT；歷史 closed 部位保留也無妨
        for (PositionEntity p : positionRepository.findByStatus("OPEN")) {
            p.setStatus("CLOSED");
            p.setClosedAt(LocalDateTime.now());
            p.setExitReason("TEST_CLEANUP");
            positionRepository.save(p);
        }
        ledgerRepository.deleteAll();

        // config 維持 reserved=0，notes 清空
        CapitalConfigEntity cfg = configRepository.findById(1L)
                .orElseGet(() -> {
                    CapitalConfigEntity e = new CapitalConfigEntity();
                    e.setId(1L);
                    return e;
                });
        cfg.setReservedCash(BigDecimal.ZERO);
        cfg.setNotes(null);
        configRepository.save(cfg);

        // 基準：先寫 1,000,000 INITIAL_BALANCE
        ledgerService.recordInitialBalance(new BigDecimal("1000000"), "test seed");
    }

    @Test
    void createPosition_shouldDeductCash_withBuyOpenAndFee() {
        String sym = uniqueSymbol("T");
        BigDecimal before = capitalService.getCashBalance();

        PositionResponse p = positionService.create(new PositionCreateRequest(
                sym, "測試A", "LONG",
                new BigDecimal("1000"), new BigDecimal("100"),
                null, null, null, null,
                "unit test", null, "SETUP"));

        // cost = 1000 × 100 = 100,000；fee = max(20, 100000×0.1425%) = 143
        BigDecimal after = capitalService.getCashBalance();
        assertThat(before.subtract(after))
                .as("建倉應扣 cost + fee")
                .isEqualByComparingTo(new BigDecimal("100143"));

        // ledger 有 BUY_OPEN 與 FEE
        List<CapitalLedgerEntity> entries = ledgerRepository.findByPositionIdOrderByOccurredAtAsc(p.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(CapitalLedgerEntity::getLedgerType))
                .containsExactlyInAnyOrder(LedgerType.BUY_OPEN, LedgerType.FEE);
    }

    @Test
    void closePosition_shouldAddProceeds_minusFeeAndTax() {
        String sym = uniqueSymbol("T");
        PositionResponse p = positionService.create(new PositionCreateRequest(
                sym, "測試B", "LONG",
                new BigDecimal("1000"), new BigDecimal("100"),
                null, null, null, null, null, null, "SETUP"));

        BigDecimal afterOpen = capitalService.getCashBalance();

        positionService.close(p.id(), new PositionCloseRequest(
                new BigDecimal("110"), LocalDateTime.now(), "MANUAL", "test close"));

        // 出清 proceeds = 1000 × 110 = 110,000；fee = max(20, 110000×0.1425%) = 157；tax = 110000×0.3% = 330
        BigDecimal afterClose = capitalService.getCashBalance();
        assertThat(afterClose.subtract(afterOpen))
                .as("出清淨入帳 = proceeds − fee − tax")
                .isEqualByComparingTo(new BigDecimal("109513"));

        // ledger 有 SELL_CLOSE / FEE / TAX
        List<CapitalLedgerEntity> entries = ledgerRepository.findByPositionIdOrderByOccurredAtAsc(p.id());
        assertThat(entries.stream().map(CapitalLedgerEntity::getLedgerType))
                .contains(LedgerType.BUY_OPEN, LedgerType.FEE, LedgerType.SELL_CLOSE, LedgerType.TAX);
    }

    @Test
    void partialClose_shouldAddPartialProceeds() {
        String sym = uniqueSymbol("T");
        PositionResponse p = positionService.create(new PositionCreateRequest(
                sym, "測試C", "LONG",
                new BigDecimal("2000"), new BigDecimal("50"),
                null, null, null, null, null, null, "SETUP"));

        BigDecimal afterOpen = capitalService.getCashBalance();

        // 賣 800 股 @55
        positionService.partialClose(p.id(), new PositionPartialCloseRequest(
                new BigDecimal("800"), new BigDecimal("55"),
                LocalDateTime.now(), "TAKE_PROFIT_1", null));

        // proceeds = 800 × 55 = 44,000；fee = max(20, 44000×0.1425%) = 63；tax = 44000×0.3% = 132
        BigDecimal afterPartial = capitalService.getCashBalance();
        assertThat(afterPartial.subtract(afterOpen))
                .as("部分賣出淨入帳 = proceeds − fee − tax")
                .isEqualByComparingTo(new BigDecimal("43805"));

        // 剩餘持倉 1200 股
        PositionEntity remaining = positionRepository.findById(p.id()).orElseThrow();
        assertThat(remaining.getStatus()).isEqualTo("OPEN");
        assertThat(remaining.getQty()).isEqualByComparingTo(new BigDecimal("1200"));
    }

    @Test
    void summary_shouldReflectLedgerAndOpenCost() {
        String sym = uniqueSymbol("T");
        positionService.create(new PositionCreateRequest(
                sym, "測試D", "LONG",
                new BigDecimal("500"), new BigDecimal("200"),
                null, null, null, null, null, null, "SETUP"));

        CapitalSummaryResponse s = capitalService.getSummary();
        // cashBalance = 1,000,000 − 100,000 − 143 = 899,857
        assertThat(s.cashBalance()).isEqualByComparingTo(new BigDecimal("899857"));
        // investedCost = 500 × 200 = 100,000
        assertThat(s.investedCost()).isEqualByComparingTo(new BigDecimal("100000"));
        // availableCash = cashBalance − reservedCash (= 0)
        assertThat(s.availableCash()).isEqualByComparingTo(new BigDecimal("899857"));
        // totalEquity = cashBalance + investedValue(fallback investedCost) = 999,857
        assertThat(s.totalEquity()).isEqualByComparingTo(new BigDecimal("999857"));
        assertThat(s.openPositionCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void deposit_withdraw_adjust_shouldUpdateBalance() {
        BigDecimal start = capitalService.getCashBalance();

        ledgerService.recordDeposit(new BigDecimal("50000"), "test deposit", "TEST");
        assertThat(capitalService.getCashBalance())
                .isEqualByComparingTo(start.add(new BigDecimal("50000")));

        ledgerService.recordWithdraw(new BigDecimal("20000"), "test withdraw", "TEST");
        assertThat(capitalService.getCashBalance())
                .isEqualByComparingTo(start.add(new BigDecimal("30000")));

        ledgerService.recordManualAdjust(new BigDecimal("-1000"), "test adjust", "TEST");
        assertThat(capitalService.getCashBalance())
                .isEqualByComparingTo(start.add(new BigDecimal("29000")));
    }

    @Test
    void withdraw_exceedingBalance_shouldThrow() {
        BigDecimal balance = capitalService.getCashBalance();
        BigDecimal tooMuch = balance.add(new BigDecimal("1"));

        assertThatThrownBy(() ->
                ledgerService.recordWithdraw(tooMuch, "overdraft", "TEST"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSUFFICIENT_CASH");
    }

    @Test
    void createPosition_whenCashInsufficient_shouldThrow() {
        // 先把錢提到剛好不夠 cost+fee
        BigDecimal balance = capitalService.getCashBalance();
        // 留 1,000 元在帳上（不夠買 10000×100=1,000,000）
        ledgerService.recordWithdraw(balance.subtract(new BigDecimal("1000")),
                "drain", "TEST");

        String sym = uniqueSymbol("T");
        assertThatThrownBy(() -> positionService.create(new PositionCreateRequest(
                sym, "測試E", "LONG",
                new BigDecimal("10000"), new BigDecimal("100"),
                null, null, null, null, null, null, "SETUP")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSUFFICIENT_CASH");

        // 驗證 position 沒建成功
        assertThat(positionRepository.findTopBySymbolAndStatus(sym, "OPEN")).isEmpty();
    }

    @Test
    void configUpdate_withTargetCashBalance_shouldWriteManualAdjust() {
        BigDecimal current = capitalService.getCashBalance();
        BigDecimal target  = current.add(new BigDecimal("12345"));

        capitalService.updateConfig(target, null, "test target");

        assertThat(capitalService.getCashBalance()).isEqualByComparingTo(target);

        // 最新一筆應為 MANUAL_ADJUST +12345
        List<CapitalLedgerEntity> recent = ledgerRepository
                .findAllByOrderByOccurredAtDescIdDesc(org.springframework.data.domain.PageRequest.of(0, 5));
        assertThat(recent.get(0).getLedgerType()).isEqualTo(LedgerType.MANUAL_ADJUST);
        assertThat(recent.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("12345"));
    }

    @Test
    void reservedCash_shouldReduceAvailableCashButNotCashBalance() {
        BigDecimal balance = capitalService.getCashBalance();

        capitalService.updateConfig(null, new BigDecimal("100000"), null);

        // cashBalance 不變
        assertThat(capitalService.getCashBalance()).isEqualByComparingTo(balance);
        // availableCash = cashBalance − 100000
        assertThat(capitalService.getAvailableCash())
                .isEqualByComparingTo(balance.subtract(new BigDecimal("100000")));
    }
}
