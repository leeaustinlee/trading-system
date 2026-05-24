package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "theme_replay_edge",
        uniqueConstraints = @UniqueConstraint(columnNames = {"trading_date", "theme_tag", "from_symbol", "to_symbol", "edge_type"}),
        indexes = {
                @Index(name = "idx_theme_replay_edge_date_theme", columnList = "trading_date, theme_tag"),
                @Index(name = "idx_theme_replay_edge_from", columnList = "from_symbol, trading_date"),
                @Index(name = "idx_theme_replay_edge_to", columnList = "to_symbol, trading_date")
        })
public class ThemeReplayEdgeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trading_date", nullable = false) private LocalDate tradingDate;
    @Column(name = "theme_tag", nullable = false, length = 100) private String themeTag;
    @Column(name = "from_symbol", nullable = false, length = 20) private String fromSymbol;
    @Column(name = "to_symbol", nullable = false, length = 20) private String toSymbol;
    @Column(name = "edge_type", nullable = false, length = 40) private String edgeType;
    @Column(name = "confidence", precision = 8, scale = 4) private BigDecimal confidence;
    @Column(name = "reason", length = 500) private String reason;
    @Column(name = "payload_json", columnDefinition = "json") private String payloadJson;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getFromSymbol() { return fromSymbol; }
    public void setFromSymbol(String fromSymbol) { this.fromSymbol = fromSymbol; }
    public String getToSymbol() { return toSymbol; }
    public void setToSymbol(String toSymbol) { this.toSymbol = toSymbol; }
    public String getEdgeType() { return edgeType; }
    public void setEdgeType(String edgeType) { this.edgeType = edgeType; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
