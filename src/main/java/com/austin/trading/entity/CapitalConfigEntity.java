package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 資金設定（singleton，id 永遠為 1）。
 *
 * <p><b>v3 語義變更：</b>現金餘額主帳改為 {@code capital_ledger}；
 * 本表降級為「設定欄位」，保留兩個用途：</p>
 * <ol>
 *   <li>{@code reserved_cash}：保留備用金（風控用，不參與買賣可動用計算）</li>
 *   <li>{@code notes}：使用者自由備註</li>
 * </ol>
 *
 * <p>{@code available_cash} 欄位保留僅為遷移來源（bootstrap 時讀一次寫入 INITIAL_BALANCE）
 * 與舊 API 相容；<b>新程式碼請改讀 {@code CapitalService.getCashBalance()}</b>。</p>
 */
@Entity
@Table(name = "capital_config")
public class CapitalConfigEntity {

    @Id
    private Long id = 1L;

    /**
     * <b>Legacy 欄位</b>：v2 以前的「可動用現金」。
     * v3 起主帳改為 {@code capital_ledger}；此欄位僅作為首次啟動的遷移來源，
     * 之後不會被主流程寫入。讀取請改 {@link com.austin.trading.service.CapitalService#getCashBalance()}。
     */
    @Column(name = "available_cash", precision = 14, scale = 2)
    private BigDecimal availableCash = BigDecimal.ZERO;

    /** 保留備用金（風控）：計算 availableCash 時從 cashBalance 扣除 */
    @Column(name = "reserved_cash", precision = 14, scale = 2)
    private BigDecimal reservedCash = BigDecimal.ZERO;

    /** 備註（例如：2026-04 帳戶重置為 30 萬） */
    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getAvailableCash() { return availableCash; }
    public void setAvailableCash(BigDecimal availableCash) { this.availableCash = availableCash; }
    public BigDecimal getReservedCash() { return reservedCash; }
    public void setReservedCash(BigDecimal reservedCash) { this.reservedCash = reservedCash; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
