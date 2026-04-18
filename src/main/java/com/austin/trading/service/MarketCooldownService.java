package com.austin.trading.service;

import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.DailyPnlRepository;
import com.austin.trading.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 全市場冷卻服務。
 * <p>連續虧損 N 筆或當日累計虧損超過閾值 → 禁止新倉。</p>
 */
@Service
public class MarketCooldownService {

    private final PositionRepository positionRepository;
    private final DailyPnlRepository dailyPnlRepository;
    private final ScoreConfigService config;

    public MarketCooldownService(PositionRepository positionRepository,
                                  DailyPnlRepository dailyPnlRepository,
                                  ScoreConfigService config) {
        this.positionRepository = positionRepository;
        this.dailyPnlRepository = dailyPnlRepository;
        this.config = config;
    }

    public record MarketCooldownResult(boolean blocked, String reason) {}

    /**
     * 判斷是否處於全市場冷卻狀態。
     */
    public MarketCooldownResult check() {
        if (!config.getBoolean("trading.cooldown.enabled", true)) {
            return new MarketCooldownResult(false, null);
        }

        // 1. 連續虧損筆數檢查
        int maxConsecutiveLoss = config.getInt("trading.cooldown.consecutive_loss_max", 3);
        int consecutiveLosses = countRecentConsecutiveLosses();
        if (consecutiveLosses >= maxConsecutiveLoss) {
            return new MarketCooldownResult(true,
                    "連續虧損 " + consecutiveLosses + " 筆（上限 " + maxConsecutiveLoss + "），暫停交易");
        }

        // 2. 當日累計虧損檢查
        BigDecimal dailyLimit = config.getDecimal("trading.cooldown.daily_loss_limit", new BigDecimal("5000"));
        BigDecimal todayPnl = getTodayRealizedPnl();
        if (todayPnl.compareTo(dailyLimit.negate()) <= 0) {
            return new MarketCooldownResult(true,
                    "當日累計虧損 " + todayPnl.toPlainString() + " 已達上限 -" + dailyLimit.toPlainString());
        }

        return new MarketCooldownResult(false, null);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    /** 計算最近連續虧損筆數（從最近一筆往回數，遇到獲利或平手就停） */
    private int countRecentConsecutiveLosses() {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<PositionEntity> recent = positionRepository.findClosedBetween(
                since, LocalDateTime.now());

        // 已按 closedAt DESC 排序
        int count = 0;
        for (PositionEntity p : recent) {
            if (p.getRealizedPnl() != null && p.getRealizedPnl().signum() < 0) {
                count++;
            } else {
                break; // 遇到非虧損就停
            }
        }
        return count;
    }

    /** 取得今日已實現損益加總 */
    private BigDecimal getTodayRealizedPnl() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        BigDecimal sum = positionRepository.sumRealizedPnlBetween(start, end);
        return sum != null ? sum : BigDecimal.ZERO;
    }
}
