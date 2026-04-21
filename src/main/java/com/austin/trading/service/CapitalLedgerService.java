package com.austin.trading.service;

import com.austin.trading.entity.CapitalLedgerEntity;
import com.austin.trading.entity.LedgerType;
import com.austin.trading.repository.CapitalLedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 資金流水寫入（底層；所有現金變動的唯一入口）。
 *
 * <p>語義契約：</p>
 * <ul>
 *   <li>{@code amount} 一律 <b>signed</b>：正=流入、負=流出；caller 傳正負由此層嚴格驗證。</li>
 *   <li>入金 / 出金 / 建倉 / 賣出 / 費稅 / 手動調帳 都經過此層，不可繞路改 capital_config。</li>
 *   <li>寫入皆不可改；修正請再寫一筆 {@code MANUAL_ADJUST} 抵銷。</li>
 * </ul>
 */
@Service
public class CapitalLedgerService {

    private final CapitalLedgerRepository ledgerRepository;

    public CapitalLedgerService(CapitalLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /** 現金餘額 = ledger SUM(amount)；空表回 ZERO */
    public BigDecimal getCashBalance() {
        BigDecimal sum = ledgerRepository.sumAllAmount();
        return sum == null ? BigDecimal.ZERO : sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** 入金（+） */
    @Transactional
    public CapitalLedgerEntity recordDeposit(BigDecimal amount, String note, String source) {
        requirePositive(amount, "deposit amount must be positive");
        return write(LedgerType.DEPOSIT, amount.abs(), null, null, null,
                source == null ? "MANUAL" : source, note, null);
    }

    /** 出金（-）；現金不足時 HTTP 409 */
    @Transactional
    public CapitalLedgerEntity recordWithdraw(BigDecimal amount, String note, String source) {
        requirePositive(amount, "withdraw amount must be positive");
        BigDecimal absAmount = amount.abs();
        BigDecimal balance = getCashBalance();
        if (balance.compareTo(absAmount) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "INSUFFICIENT_CASH: balance=" + balance + " withdraw=" + absAmount);
        }
        return write(LedgerType.WITHDRAW, absAmount.negate(), null, null, null,
                source == null ? "MANUAL" : source, note, null);
    }

    /** 手動調帳；正負皆可（正=增加、負=減少）；減少時若導致負餘額也擋下 */
    @Transactional
    public CapitalLedgerEntity recordManualAdjust(BigDecimal signedAmount, String note, String source) {
        requireNonZero(signedAmount, "adjust amount must not be zero");
        if (signedAmount.signum() < 0) {
            BigDecimal balance = getCashBalance();
            if (balance.add(signedAmount).signum() < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "INSUFFICIENT_CASH: balance=" + balance + " adjust=" + signedAmount);
            }
        }
        return write(LedgerType.MANUAL_ADJUST, signedAmount, null, null, null,
                source == null ? "MANUAL" : source, note, null);
    }

    /** 建倉成交金額寫入（cost 為正值，此處轉負寫入）*/
    @Transactional
    public CapitalLedgerEntity recordBuyOpen(Long positionId, String symbol, BigDecimal cost,
                                              LocalDate tradeDate, String note) {
        requirePositive(cost, "buy-open cost must be positive");
        return write(LedgerType.BUY_OPEN, cost.abs().negate(), symbol, positionId, tradeDate,
                "POSITION_OPEN", note, null);
    }

    /** 全部出清成交金額寫入（proceeds 為正值）*/
    @Transactional
    public CapitalLedgerEntity recordSellClose(Long positionId, String symbol, BigDecimal proceeds,
                                                LocalDate tradeDate, String note) {
        requirePositive(proceeds, "sell-close proceeds must be positive");
        return write(LedgerType.SELL_CLOSE, proceeds.abs(), symbol, positionId, tradeDate,
                "POSITION_CLOSE", note, null);
    }

    /** 部分出清成交金額寫入 */
    @Transactional
    public CapitalLedgerEntity recordSellPartial(Long positionId, String symbol, BigDecimal proceeds,
                                                  LocalDate tradeDate, String note) {
        requirePositive(proceeds, "sell-partial proceeds must be positive");
        return write(LedgerType.SELL_PARTIAL, proceeds.abs(), symbol, positionId, tradeDate,
                "POSITION_PARTIAL", note, null);
    }

    /** 手續費（正值傳入，此處轉負寫入）*/
    @Transactional
    public CapitalLedgerEntity recordFee(Long positionId, String symbol, BigDecimal fee,
                                          LocalDate tradeDate, String note) {
        if (fee == null || fee.signum() == 0) return null;
        return write(LedgerType.FEE, fee.abs().negate(), symbol, positionId, tradeDate,
                "POSITION_FEE", note, null);
    }

    /** 交易稅（正值傳入，此處轉負寫入）*/
    @Transactional
    public CapitalLedgerEntity recordTax(Long positionId, String symbol, BigDecimal tax,
                                          LocalDate tradeDate, String note) {
        if (tax == null || tax.signum() == 0) return null;
        return write(LedgerType.TAX, tax.abs().negate(), symbol, positionId, tradeDate,
                "POSITION_TAX", note, null);
    }

    /** 啟動遷移用：把 capital_config.available_cash 寫成 opening balance */
    @Transactional
    public CapitalLedgerEntity recordInitialBalance(BigDecimal amount, String note) {
        if (amount == null || amount.signum() == 0) return null;
        return write(LedgerType.INITIAL_BALANCE, amount, null, null, null,
                "BOOTSTRAP", note, null);
    }

    // ── 底層 ───────────────────────────────────────────────────────────

    private CapitalLedgerEntity write(LedgerType type, BigDecimal signedAmount,
                                       String symbol, Long positionId, LocalDate tradeDate,
                                       String source, String note, String payloadJson) {
        CapitalLedgerEntity e = new CapitalLedgerEntity();
        e.setLedgerType(type);
        e.setAmount(signedAmount.setScale(4, RoundingMode.HALF_UP));
        e.setSymbol(symbol);
        e.setPositionId(positionId);
        e.setTradeDate(tradeDate);
        e.setOccurredAt(LocalDateTime.now());
        e.setSource(source);
        e.setNote(note);
        e.setPayloadJson(payloadJson);
        return ledgerRepository.save(e);
    }

    private static void requirePositive(BigDecimal v, String msg) {
        if (v == null || v.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
    }

    private static void requireNonZero(BigDecimal v, String msg) {
        if (v == null || v.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
    }
}
