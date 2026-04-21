package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 資金流水（不可變追加簿）。
 *
 * <ul>
 *   <li>所有現金變動（入金、出金、買賣成交、費用、稅、手動調整）都寫一筆。</li>
 *   <li>{@code amount} 為 <b>signed</b>：正值=現金流入、負值=現金流出。</li>
 *   <li>當前現金餘額 = {@code SUM(amount)}；{@code capital_config.available_cash} 不再是主帳。</li>
 *   <li>寫入後禁止改資料；修正請用新的 {@code MANUAL_ADJUST} 抵銷。</li>
 * </ul>
 */
@Entity
@Table(
        name = "capital_ledger",
        indexes = {
                @Index(name = "idx_capital_ledger_occurred", columnList = "occurred_at"),
                @Index(name = "idx_capital_ledger_type",     columnList = "ledger_type"),
                @Index(name = "idx_capital_ledger_position", columnList = "position_id"),
                @Index(name = "idx_capital_ledger_symbol",   columnList = "symbol")
        }
)
public class CapitalLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LedgerType 字串值；存字串而非 ordinal，避免 enum 重排風險 */
    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_type", nullable = false, length = 20)
    private LedgerType ledgerType;

    /** signed amount（入正出負），14 位精度到小數 4 位 */
    @Column(name = "amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal amount;

    /** 關聯標的（若有）*/
    @Column(name = "symbol", length = 20)
    private String symbol;

    /** 關聯持倉 id（loose coupling，非 FK）*/
    @Column(name = "position_id")
    private Long positionId;

    /** 交易日（對帳用；與 occurredAt 分離是為了手動日期補登）*/
    @Column(name = "trade_date")
    private LocalDate tradeDate;

    /** 事件發生時間 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 備註（free text）*/
    @Column(name = "note", columnDefinition = "text")
    private String note;

    /** 來源代碼：POSITION_OPEN / POSITION_CLOSE / POSITION_PARTIAL / MANUAL / BOOTSTRAP / UI 等 */
    @Column(name = "source", length = 30)
    private String source;

    /** 擴充欄位（fee/tax 組成、券商原始數字等）*/
    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // ── getters / setters ────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LedgerType getLedgerType() { return ledgerType; }
    public void setLedgerType(LedgerType ledgerType) { this.ledgerType = ledgerType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
