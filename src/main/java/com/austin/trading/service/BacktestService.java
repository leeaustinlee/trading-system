package com.austin.trading.service;

import com.austin.trading.engine.BacktestMetricsEngine;
import com.austin.trading.engine.BacktestMetricsEngine.*;
import com.austin.trading.entity.BacktestRunEntity;
import com.austin.trading.entity.BacktestTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.BacktestRunRepository;
import com.austin.trading.repository.BacktestTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BacktestRunRepository runRepository;
    private final BacktestTradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final BacktestMetricsEngine metricsEngine;
    private final ScoreConfigService configService;
    private final ObjectMapper objectMapper;

    public BacktestService(BacktestRunRepository runRepository,
                            BacktestTradeRepository tradeRepository,
                            PositionRepository positionRepository,
                            BacktestMetricsEngine metricsEngine,
                            ScoreConfigService configService,
                            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.metricsEngine = metricsEngine;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BacktestRunEntity runBacktest(LocalDate startDate, LocalDate endDate, String runName, String notes) {
        // 建立 run 紀錄
        BacktestRunEntity run = new BacktestRunEntity();
        run.setRunName(runName != null ? runName : "Backtest " + startDate + " ~ " + endDate);
        run.setRunType("RANGE_BACKTEST");
        run.setStartDate(startDate);
        run.setEndDate(endDate);
        run.setConfigVersion(configService.getString("scoring.version", "v2.0-bc-sniper"));
        run.setConfigSnapshotJson(captureConfigSnapshot());
        run.setNotes(notes);
        run.setStatus("RUNNING");
        run = runRepository.save(run);

        try {
            // 從 position history 重建交易列表
            LocalDateTime from = startDate.atStartOfDay();
            LocalDateTime to = endDate.plusDays(1).atStartOfDay();
            List<PositionEntity> closedPositions = positionRepository.findClosedBetween(from, to);

            List<BacktestTradeInput> tradeInputs = new ArrayList<>();
            for (PositionEntity pos : closedPositions) {
                BacktestTradeEntity trade = toBacktestTrade(run.getId(), pos);
                tradeRepository.save(trade);

                tradeInputs.add(new BacktestTradeInput(
                        trade.getPnlPct(), trade.getHoldingDays() != null ? trade.getHoldingDays() : 0,
                        trade.getMfePct(), trade.getMaePct(),
                        trade.getEntryTriggerType()));
            }

            // 計算績效指標
            BacktestMetricsResult metrics = metricsEngine.compute(tradeInputs);

            // 更新 run
            run.setTotalTrades(metrics.totalTrades());
            run.setWinCount(metrics.winCount());
            run.setLossCount(metrics.lossCount());
            run.setWinRate(metrics.winRate());
            run.setAvgReturnPct(metrics.avgReturnPct());
            run.setAvgHoldingDays(metrics.avgHoldingDays());
            run.setMaxDrawdownPct(metrics.maxDrawdownPct());
            run.setProfitFactor(metrics.profitFactor());
            run.setBestTradePct(metrics.bestTradePct());
            run.setWorstTradePct(metrics.worstTradePct());
            run.setTotalPnl(metrics.totalPnl());
            run.setStatus("SUCCESS");
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);

            log.info("[Backtest] 完成 runId={} trades={} winRate={}%",
                    run.getId(), metrics.totalTrades(), metrics.winRate());
            return run;

        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setNotes((run.getNotes() != null ? run.getNotes() + " | " : "") + "ERROR: " + e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);
            log.error("[Backtest] 失敗 runId={}: {}", run.getId(), e.getMessage());
            throw e;
        }
    }

    public List<BacktestRunEntity> getAllRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc();
    }

    public BacktestRunEntity getRun(Long id) {
        return runRepository.findById(id).orElseThrow(() -> new RuntimeException("Backtest run not found: " + id));
    }

    public List<BacktestTradeEntity> getTrades(Long runId) {
        return tradeRepository.findByBacktestRunIdOrderByEntryDateAsc(runId);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private BacktestTradeEntity toBacktestTrade(Long runId, PositionEntity pos) {
        BigDecimal pnlPct = BigDecimal.ZERO;
        if (pos.getClosePrice() != null && pos.getAvgCost() != null && pos.getAvgCost().signum() > 0) {
            pnlPct = pos.getClosePrice().subtract(pos.getAvgCost())
                    .divide(pos.getAvgCost(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }
        int holdingDays = pos.getOpenedAt() != null && pos.getClosedAt() != null
                ? (int) ChronoUnit.DAYS.between(pos.getOpenedAt().toLocalDate(), pos.getClosedAt().toLocalDate())
                : 0;

        // 從 exitReason/note 推斷 entry trigger type
        String triggerType = inferEntryTriggerType(pos);

        BacktestTradeEntity t = new BacktestTradeEntity();
        t.setBacktestRunId(runId);
        t.setPositionId(pos.getId());
        t.setSymbol(pos.getSymbol());
        t.setStockName(pos.getStockName());
        t.setEntryDate(pos.getOpenedAt() != null ? pos.getOpenedAt().toLocalDate() : LocalDate.now());
        t.setExitDate(pos.getClosedAt() != null ? pos.getClosedAt().toLocalDate() : null);
        t.setEntryPrice(pos.getAvgCost());
        t.setExitPrice(pos.getClosePrice());
        t.setPnlPct(pnlPct);
        t.setHoldingDays(holdingDays);
        t.setEntryTriggerType(triggerType);
        t.setEntryReason(pos.getNote());
        t.setExitReason(pos.getExitReason());
        return t;
    }

    private String inferEntryTriggerType(PositionEntity pos) {
        String note = pos.getNote() != null ? pos.getNote().toLowerCase() : "";
        if (note.contains("breakout") || note.contains("突破")) return "BREAKOUT";
        if (note.contains("pullback") || note.contains("回測")) return "PULLBACK";
        if (note.contains("reversal") || note.contains("轉強")) return "REVERSAL";
        if (note.contains("watchlist") || note.contains("ready")) return "WATCHLIST_READY";
        return "UNKNOWN";
    }

    private String captureConfigSnapshot() {
        try {
            Map<String, String> snapshot = new java.util.LinkedHashMap<>();
            configService.getAll().forEach(c -> snapshot.put(c.configKey(), c.configValue()));
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
