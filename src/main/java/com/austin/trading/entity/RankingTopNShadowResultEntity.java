package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Shadow-only Top-N ranking result skeleton.
 *
 * <p>Captures ranking output for trace/replay comparison without changing
 * production ranking, candidate, buy/sell, or risk decisions.</p>
 */
@Entity
@Table(name = "ranking_topn_shadow_result",
        uniqueConstraints = @UniqueConstraint(name = "uk_ranking_topn_shadow_date_run_symbol",
                columnNames = {"trading_date", "run_id", "symbol"}),
        indexes = {
                @Index(name = "idx_ranking_topn_shadow_date_rank", columnList = "trading_date, ranking_rank"),
                @Index(name = "idx_ranking_topn_shadow_symbol_date", columnList = "symbol, trading_date"),
                @Index(name = "idx_ranking_topn_shadow_theme_date", columnList = "theme_tag, trading_date"),
                @Index(name = "idx_ranking_topn_shadow_run", columnList = "run_id")
        })
public class RankingTopNShadowResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;
    @Column(name = "run_id", nullable = false, length = 80)
    private String runId;
    @Column(name = "snapshot_id")
    private Long snapshotId;
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;
    @Column(name = "stock_name", length = 120)
    private String stockName;
    @Column(name = "theme_tag", length = 120)
    private String themeTag;
    @Column(name = "bucket", length = 40)
    private String bucket;
    @Column(name = "current_selected")
    private Boolean currentSelected;
    @Column(name = "would_select_top5")
    private Boolean wouldSelectTop5;
    @Column(name = "would_select_top10")
    private Boolean wouldSelectTop10;
    @Column(name = "would_select_top20")
    private Boolean wouldSelectTop20;
    @Column(name = "ranking_rank")
    private Integer rankingRank;
    @Column(name = "ranking_score", precision = 12, scale = 4)
    private BigDecimal rankingScore;
    @Column(name = "ranking_status", length = 40)
    private String rankingStatus;
    @Column(name = "ranking_reason", length = 500)
    private String rankingReason;
    @Column(name = "candidate_id")
    private Long candidateId;
    @Column(name = "source_trace_id")
    private Long sourceTraceId;
    @Column(name = "actual_return_1d", precision = 12, scale = 4)
    private BigDecimal actualReturn1d;
    @Column(name = "actual_return_5d", precision = 12, scale = 4)
    private BigDecimal actualReturn5d;
    @Column(name = "actual_return_10d", precision = 12, scale = 4)
    private BigDecimal actualReturn10d;
    @Column(name = "max_drawdown_10d", precision = 12, scale = 4)
    private BigDecimal maxDrawdown10d;
    @Column(name = "missed_by_top3")
    private Boolean missedByTop3;
    @Column(name = "score_breakdown_json", columnDefinition = "json")
    private String scoreBreakdownJson;
    @Column(name = "trace_source", nullable = false, length = 40)
    private String traceSource = "SHADOW";
    @Column(name = "trace_status", nullable = false, length = 40)
    private String traceStatus = "ACTIVE";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) { createdAt = now; }
        if (updatedAt == null) { updatedAt = now; }
        if (traceSource == null) { traceSource = "SHADOW"; }
        if (traceStatus == null) { traceStatus = "ACTIVE"; }
    }

    @PreUpdate
    public void preUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getThemeTag() { return themeTag; }
    public void setThemeTag(String themeTag) { this.themeTag = themeTag; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public Boolean getCurrentSelected() { return currentSelected; }
    public void setCurrentSelected(Boolean currentSelected) { this.currentSelected = currentSelected; }
    public Boolean getWouldSelectTop5() { return wouldSelectTop5; }
    public void setWouldSelectTop5(Boolean wouldSelectTop5) { this.wouldSelectTop5 = wouldSelectTop5; }
    public Boolean getWouldSelectTop10() { return wouldSelectTop10; }
    public void setWouldSelectTop10(Boolean wouldSelectTop10) { this.wouldSelectTop10 = wouldSelectTop10; }
    public Boolean getWouldSelectTop20() { return wouldSelectTop20; }
    public void setWouldSelectTop20(Boolean wouldSelectTop20) { this.wouldSelectTop20 = wouldSelectTop20; }
    public Integer getRankingRank() { return rankingRank; }
    public void setRankingRank(Integer rankingRank) { this.rankingRank = rankingRank; }
    public BigDecimal getRankingScore() { return rankingScore; }
    public void setRankingScore(BigDecimal rankingScore) { this.rankingScore = rankingScore; }
    public String getRankingStatus() { return rankingStatus; }
    public void setRankingStatus(String rankingStatus) { this.rankingStatus = rankingStatus; }
    public String getRankingReason() { return rankingReason; }
    public void setRankingReason(String rankingReason) { this.rankingReason = rankingReason; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public Long getSourceTraceId() { return sourceTraceId; }
    public void setSourceTraceId(Long sourceTraceId) { this.sourceTraceId = sourceTraceId; }
    public BigDecimal getActualReturn1d() { return actualReturn1d; }
    public void setActualReturn1d(BigDecimal actualReturn1d) { this.actualReturn1d = actualReturn1d; }
    public BigDecimal getActualReturn5d() { return actualReturn5d; }
    public void setActualReturn5d(BigDecimal actualReturn5d) { this.actualReturn5d = actualReturn5d; }
    public BigDecimal getActualReturn10d() { return actualReturn10d; }
    public void setActualReturn10d(BigDecimal actualReturn10d) { this.actualReturn10d = actualReturn10d; }
    public BigDecimal getMaxDrawdown10d() { return maxDrawdown10d; }
    public void setMaxDrawdown10d(BigDecimal maxDrawdown10d) { this.maxDrawdown10d = maxDrawdown10d; }
    public Boolean getMissedByTop3() { return missedByTop3; }
    public void setMissedByTop3(Boolean missedByTop3) { this.missedByTop3 = missedByTop3; }
    public String getScoreBreakdownJson() { return scoreBreakdownJson; }
    public void setScoreBreakdownJson(String scoreBreakdownJson) { this.scoreBreakdownJson = scoreBreakdownJson; }
    public String getTraceSource() { return traceSource; }
    public void setTraceSource(String traceSource) { this.traceSource = traceSource; }
    public String getTraceStatus() { return traceStatus; }
    public void setTraceStatus(String traceStatus) { this.traceStatus = traceStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
