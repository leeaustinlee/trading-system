package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 個股↔題材 對應表。
 * <p>
 * 一檔股票可屬多個題材（如台積電屬於 AI算力 + CoWoS + 台廠供應鏈）。
 * source=MANUAL：人工建立；AUTO：系統依成交族群自動推算；CODEX：Codex 標註。
 * </p>
 */
@Entity
@Table(name = "stock_theme_mapping",
    uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "theme_tag"}))
public class StockThemeMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "stock_name", length = 120)
    private String stockName;

    @Column(name = "theme_tag", nullable = false, length = 100)
    private String themeTag;

    @Column(name = "sub_theme", length = 100)
    private String subTheme;

    /** AI / 台廠供應鏈 / 政策 / 傳產 / 金融 */
    @Column(name = "theme_category", length = 50)
    private String themeCategory;

    /** MANUAL / AUTO / CODEX / CLAUDE */
    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "confidence", precision = 4, scale = 2)
    private BigDecimal confidence = BigDecimal.ONE;

    @Column(name = "is_active")
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getSubTheme() { return subTheme; }
    public void setSubTheme(String subTheme) { this.subTheme = subTheme; }
    public String getThemeCategory() { return themeCategory; }
    public void setThemeCategory(String themeCategory) { this.themeCategory = themeCategory; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
