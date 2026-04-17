package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 資金設定（singleton，id 永遠為 1）。
 * 記錄用戶可動用現金，供 Codex 決策時參考避免選千金股。
 */
@Entity
@Table(name = "capital_config")
public class CapitalConfigEntity {

    @Id
    private Long id = 1L;

    /** 可動用現金（含保留備用金，不含已投入股票的金額） */
    @Column(name = "available_cash", precision = 14, scale = 2)
    private BigDecimal availableCash = BigDecimal.ZERO;

    /** 備註（例如：2026-04 帳戶重置為 30 萬） */
    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getAvailableCash() { return availableCash; }
    public void setAvailableCash(BigDecimal availableCash) { this.availableCash = availableCash; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
