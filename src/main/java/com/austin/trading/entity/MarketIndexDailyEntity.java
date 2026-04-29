package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P0.5 — TAIEX / 個股歷史日線收盤儲存。
 *
 * <p>支援 {@link com.austin.trading.service.regime.RealDowngradeEvaluator}
 * 的 {@code CONSEC_DOWN} / {@code TAIEX_BELOW_60MA} / {@code SEMI_WEAK} 三個
 * trigger，過去這些 trigger 因 {@code MarketIndexProvider} 只有 Noop 而
 * 永遠拿不到資料；現由 {@link com.austin.trading.scheduler.MarketIndexDataPrepJob}
 * 與 startup backfill 自動抓 TWSE 落盤至本表。</p>
 *
 * <p>{@code symbol} 用法：</p>
 * <ul>
 *   <li>{@code "t00"} → TAIEX 加權指數</li>
 *   <li>{@code "2330"} 等 → 個股代號（半導體 SEMI_WEAK 預設 2330）</li>
 * </ul>
 */
@Entity
@Table(
        name = "market_index_daily",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_market_index_symbol_date",
                        columnNames = {"symbol", "trading_date"}
                )
        },
        indexes = {
                @Index(name = "idx_market_index_date", columnList = "trading_date")
        }
)
public class MarketIndexDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "open_price", precision = 12, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 12, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 12, scale = 4)
    private BigDecimal lowPrice;

    /** 收盤價 — 必填；DB 也是 NOT NULL。 */
    @Column(name = "close_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public MarketIndexDailyEntity() {}

    public MarketIndexDailyEntity(String symbol,
                                  LocalDate tradingDate,
                                  BigDecimal openPrice,
                                  BigDecimal highPrice,
                                  BigDecimal lowPrice,
                                  BigDecimal closePrice,
                                  Long volume) {
        this.symbol      = symbol;
        this.tradingDate = tradingDate;
        this.openPrice   = openPrice;
        this.highPrice   = highPrice;
        this.lowPrice    = lowPrice;
        this.closePrice  = closePrice;
        this.volume      = volume;
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }

    public BigDecimal getOpenPrice() { return openPrice; }
    public void setOpenPrice(BigDecimal openPrice) { this.openPrice = openPrice; }

    public BigDecimal getHighPrice() { return highPrice; }
    public void setHighPrice(BigDecimal highPrice) { this.highPrice = highPrice; }

    public BigDecimal getLowPrice() { return lowPrice; }
    public void setLowPrice(BigDecimal lowPrice) { this.lowPrice = lowPrice; }

    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }

    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
