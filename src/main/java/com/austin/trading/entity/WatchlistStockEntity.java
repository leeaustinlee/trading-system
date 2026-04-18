package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "watchlist_stock",
        uniqueConstraints = @UniqueConstraint(name = "uq_watchlist_symbol", columnNames = "symbol"),
        indexes = @Index(name = "idx_watchlist_status", columnList = "watch_status"))
public class WatchlistStockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "theme_tag", length = 100)
    private String themeTag;

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "current_score", precision = 8, scale = 4)
    private BigDecimal currentScore;

    @Column(name = "highest_score", precision = 8, scale = 4)
    private BigDecimal highestScore;

    @Column(name = "watch_status", nullable = false, length = 20)
    private String watchStatus = "TRACKING";

    @Column(name = "first_seen_date", nullable = false)
    private LocalDate firstSeenDate;

    @Column(name = "last_seen_date", nullable = false)
    private LocalDate lastSeenDate;

    @Column(name = "observation_days", nullable = false)
    private int observationDays = 1;

    @Column(name = "consecutive_strong_days", nullable = false)
    private int consecutiveStrongDays = 0;

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    @Column(name = "dropped_at")
    private LocalDateTime droppedAt;

    @Column(name = "drop_reason", length = 200)
    private String dropReason;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── getters / setters ──────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public BigDecimal getCurrentScore() { return currentScore; }
    public void setCurrentScore(BigDecimal currentScore) { this.currentScore = currentScore; }
    public BigDecimal getHighestScore() { return highestScore; }
    public void setHighestScore(BigDecimal highestScore) { this.highestScore = highestScore; }
    public String getWatchStatus() { return watchStatus; }
    public void setWatchStatus(String watchStatus) { this.watchStatus = watchStatus; }
    public LocalDate getFirstSeenDate() { return firstSeenDate; }
    public void setFirstSeenDate(LocalDate firstSeenDate) { this.firstSeenDate = firstSeenDate; }
    public LocalDate getLastSeenDate() { return lastSeenDate; }
    public void setLastSeenDate(LocalDate lastSeenDate) { this.lastSeenDate = lastSeenDate; }
    public int getObservationDays() { return observationDays; }
    public void setObservationDays(int observationDays) { this.observationDays = observationDays; }
    public int getConsecutiveStrongDays() { return consecutiveStrongDays; }
    public void setConsecutiveStrongDays(int consecutiveStrongDays) { this.consecutiveStrongDays = consecutiveStrongDays; }
    public LocalDateTime getPromotedAt() { return promotedAt; }
    public void setPromotedAt(LocalDateTime promotedAt) { this.promotedAt = promotedAt; }
    public LocalDateTime getDroppedAt() { return droppedAt; }
    public void setDroppedAt(LocalDateTime droppedAt) { this.droppedAt = droppedAt; }
    public String getDropReason() { return dropReason; }
    public void setDropReason(String dropReason) { this.dropReason = dropReason; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
